package com.vera.timebricks

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One valid, full JSON backup wrapped in a bounded two-entry ZIP. Never extract paths to disk. */
internal object AutoBackupZip {
    const val FORMAT = "com.vera.timebricks.autobackup"
    const val MAX_ZIP_BYTES = 34 * 1024 * 1024
    private const val MAX_JSON_BYTES = 32 * 1024 * 1024
    private const val MAX_META_BYTES = 16 * 1024
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() &&
        bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    fun pack(json: ByteArray, whenMade: Instant = Instant.now()): ByteArray {
        require(json.isNotEmpty() && json.size <= MAX_JSON_BYTES) { "完整备份过大" }
        val manifest = JSONObject().put("format", FORMAT).put("version", 1)
            .put("createdAt", whenMade.toString()).put("payload", "backup.json")
            .put("sha256", digest(json)).toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest); zip.closeEntry()
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(json); zip.closeEntry()
            }
            output.toByteArray().also { require(it.size <= MAX_ZIP_BYTES) { "压缩包超过大小限制" } }
        }
    }

    fun unpack(zipBytes: ByteArray): ByteArray {
        require(isZip(zipBytes) && zipBytes.size <= MAX_ZIP_BYTES) { "不是支持的自动备份 ZIP" }
        var metadata: ByteArray? = null
        var payload: ByteArray? = null
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in setOf("manifest.json", "backup.json")) { "备份含未知文件" }
                require(if (entry.name == "manifest.json") metadata == null else payload == null) { "备份内有重复文件" }
                val cap = if (entry.name == "manifest.json") MAX_META_BYTES else MAX_JSON_BYTES
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count == -1) break
                    total += count
                    require(total <= MAX_JSON_BYTES.toLong() + MAX_META_BYTES && output.size() + count <= cap) { "备份解压内容过大" }
                    output.write(buffer, 0, count)
                }
                if (entry.name == "manifest.json") metadata = output.toByteArray() else payload = output.toByteArray()
                zip.closeEntry()
            }
        }
        val headerBytes = metadata ?: error("缺少自动备份信息")
        val json = payload ?: error("缺少完整 JSON 数据")
        val header = JSONObject(String(headerBytes, Charsets.UTF_8))
        require(header.keys().asSequence().toSet() == setOf("format", "version", "createdAt", "payload", "sha256") &&
            header.getString("format") == FORMAT && header.getInt("version") == 1 &&
            header.getString("payload") == "backup.json" && header.getString("sha256") == digest(json)) { "自动备份校验不通过" }
        Instant.parse(header.getString("createdAt"))
        return json
    }
}

internal data class AutoBackupStatus(
    val enabled: Boolean = false,
    val folder: String = "",
    val lastBackup: String = "",
    val error: String = ""
)

/** SAF directory authorization + WorkManager daily schedule + foreground catch-up. */
internal object AutoBackupSystem {
    private const val PREFS = "timebricks-auto-backup-v1"
    private const val WORK_NAME = "timebricks-daily-auto-zip-backup"
    private const val FILE_PREFIX = "TimeBricks-"
    private val autoName = Regex("^TimeBricks-(\\d{4}-\\d{2}-\\d{2})\\.zip$")
    private val queue = Executors.newSingleThreadExecutor()
    private val backupLock = Any()
    private val _status = MutableStateFlow(AutoBackupStatus())
    val status: StateFlow<AutoBackupStatus> = _status
    @Volatile private var restorePaused = false
    @Volatile private var restoreGeneration = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun tree(context: Context): Uri? = prefs(context).getString("tree", null)?.let(Uri::parse)

    fun initialize(context: Context) {
        updateStatus(context)
        // KEEP makes this idempotent, including after application updates.
        runCatching { schedule(context.applicationContext) }
    }
    private fun updateStatus(context: Context, error: String? = null) {
        val p = prefs(context)
        _status.value = AutoBackupStatus(
            enabled = p.getBoolean("enabled", false),
            folder = p.getString("folder", "") ?: "",
            lastBackup = p.getString("last", "") ?: "",
            error = error ?: p.getString("error", "").orEmpty()
        )
    }
    private fun report(context: Context, error: String) {
        prefs(context).edit().putString("error", error.take(110)).apply()
        Log.w("TimeBricksAutoBackup", error)
        updateStatus(context)
    }

    fun enable(context: Context, selected: Uri) {
        val app = context.applicationContext
        try {
            require(selected.scheme == "content" && DocumentsContract.isTreeUri(selected)) { "请选择系统文件夹" }
            app.contentResolver.takePersistableUriPermission(selected,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // Probe access before enabling. A revoked/unsupported provider is not a successful setup.
            list(app, selected)
            val name = selected.lastPathSegment?.substringAfterLast(':')?.ifBlank { "已选择文件夹" } ?: "已选择文件夹"
            val previousTree = prefs(app).getString("tree", null)
            val editor = prefs(app).edit().putString("tree", selected.toString())
                .putString("folder", name).putBoolean("enabled", true).remove("error")
            if (previousTree != selected.toString()) editor.remove("lastDay").remove("last")
            check(editor.commit()) { "无法保存备份设置" }
            updateStatus(app)
            schedule(app)
            checkNow(app)
        } catch (error: Exception) { report(app, "无法启用备份：${error.message ?: "文件夹授权失败"}") }
    }
    fun disable(context: Context) {
        val app = context.applicationContext
        // Preserve the folder grant: the user can re-enable without losing already saved backups.
        prefs(app).edit().putBoolean("enabled", false).remove("error").commit()
        schedule(app)
        updateStatus(app)
    }
    fun pauseRestore() { restorePaused = true; restoreGeneration++ }
    fun resumeRestore(context: Context) { restorePaused = false; checkNow(context) }

    private fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (prefs(context).getBoolean("enabled", false)) {
            val periodic = PeriodicWorkRequestBuilder<TimeBricksAutoBackupWorker>(24, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        } else workManager.cancelUniqueWork(WORK_NAME)
    }

    fun checkNow(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean("enabled", false)) return
        queue.execute { backupOnce(app) }
    }
    fun scheduledCheck(context: Context): Boolean = backupOnce(context.applicationContext)

    private data class Document(val id: String, val name: String, val mime: String)
    private fun list(context: Context, tree: Uri): List<Document> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = mutableListOf<Document>()
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result += Document(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        } ?: error("无法读取备份文件夹")
        return result
    }
    private fun uri(tree: Uri, document: Document) = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
    private fun read(context: Context, file: Uri, maximum: Int = AutoBackupZip.MAX_ZIP_BYTES): ByteArray =
        (context.contentResolver.openInputStream(file) ?: error("无法读取已保存的文件")).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                require(output.size().toLong() + count <= maximum) { "备份文件过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    private fun verify(context: Context, uri: Uri): ByteArray {
        val json = AutoBackupZip.unpack(read(context, uri))
        verifyTimeBricksBackupPayload(json)
        return json
    }

    private fun backupOnce(context: Context): Boolean = synchronized(backupLock) {
            if (restorePaused || !prefs(context).getBoolean("enabled", false)) return@synchronized true
            val generation = restoreGeneration
            try {
                if (restorePaused) return@synchronized true
                val target = tree(context) ?: error("没有备份文件夹")
                val today = LocalDate.now(ZoneId.systemDefault()).toString()
                val name = "$FILE_PREFIX$today.zip"
                val entries = list(context, target)
                val existing = entries.firstOrNull { it.name == name }
                if (existing != null) {
                    // Never overwrite a valid existing daily snapshot. Corrupt files require user action.
                    verify(context, uri(target, existing))
                    if (prefs(context).getString("lastDay", "") != today) {
                        markSuccess(context, today)
                        prune(context, target)
                    }
                    return@synchronized true
                }
                // A missing local file is not proof that an empty calendar should be backed up.
                check(java.io.File(context.filesDir, "timebricks-data-v1.json").exists()) { "本地日程尚未完成首次保存" }
                val snapshot = snapshotTimeBricksForAutoBackup(context.applicationContext as Application)
                val zip = AutoBackupZip.pack(snapshot)
                val stagedName = "$FILE_PREFIX$today.pending.zip"
                entries.filter { it.name == stagedName }.forEach { obsolete ->
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(target, obsolete)) }
                }
                // ACTION_OPEN_DOCUMENT_TREE supplies a tree URI, but createDocument requires
                // the URI of the parent *document* inside that tree. Passing the tree URI
                // directly fails with "Invalid URI" on ExternalStorageProvider.
                val folderDocument = DocumentsContract.buildDocumentUriUsingTree(
                    target, DocumentsContract.getTreeDocumentId(target))
                val staged = DocumentsContract.createDocument(context.contentResolver,
                    folderDocument, "application/zip", stagedName)
                    ?: error("无法创建备份文件")
                var renamedSuccessfully = false
                try {
                    (context.contentResolver.openOutputStream(staged, "wt") ?: error("无法写入备份文件")).use { output ->
                        output.write(zip); output.flush()
                    }
                    val confirmed = verify(context, staged)
                    check(confirmed.contentEquals(snapshot)) { "临时备份内容校验失败" }
                    // Commit only if no restore began while the snapshot was being written.
                    synchronized(ReminderSystem.lock) {
                        if (restorePaused || restoreGeneration != generation ||
                            !prefs(context).getBoolean("enabled", false) || tree(context) != target) {
                            throw IllegalStateException("备份设置或数据恢复状态已变化")
                        }
                        val renamed = DocumentsContract.renameDocument(context.contentResolver, staged, name)
                            ?: error("文件夹不支持安全重命名，请更换备份位置")
                        renamedSuccessfully = true
                        check(verify(context, renamed).contentEquals(snapshot)) { "最终备份校验失败" }
                        check(list(context, target).any { it.name == name }) { "存储位置未保留最终文件名" }
                    }
                } finally {
                    if (!renamedSuccessfully) runCatching { DocumentsContract.deleteDocument(context.contentResolver, staged) }
                }
                markSuccess(context, today)
                prune(context, target)
                true
            } catch (error: Exception) {
                if (!restorePaused && prefs(context).getBoolean("enabled", false))
                    report(context, "自动备份失败：${error.message ?: "存储位置不可用"}")
                false
            }
    }

    private fun markSuccess(context: Context, date: String) {
        val stamp = Instant.now().toString()
        prefs(context).edit().putString("lastDay", date).putString("last", stamp).remove("error").commit()
        updateStatus(context, "")
    }

    private fun prune(context: Context, folder: Uri) {
        // Only verified ZIPs bearing our explicit automatic-backup manifest may be pruned.
        val valid = list(context, folder).mapNotNull { doc ->
            val match = autoName.matchEntire(doc.name) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            if (runCatching { verify(context, uri(folder, doc)) }.isFailure) return@mapNotNull null
            date to doc
        }.sortedByDescending { it.first }
        valid.drop(7).forEach { (_, item) ->
            check(DocumentsContract.deleteDocument(context.contentResolver, uri(folder, item))) { "旧备份清理失败" }
        }
    }
}

class TimeBricksAutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (AutoBackupSystem.scheduledCheck(applicationContext))
        Result.success() else Result.retry()
}
