package com.vera.timebricks

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

internal data class AboutConfig(val repository: String, val author: String, val email: String, val qqGroup: String) {
    companion object {
        fun from(context: Context) = AboutConfig(
            context.getString(R.string.about_release_repository), context.getString(R.string.about_author),
            context.getString(R.string.about_feedback_email), context.getString(R.string.about_qq_group)
        )
    }
}

internal data class ReleaseAsset(val id: Long, val name: String, val url: String, val size: Long, val digest: String)
internal data class AppRelease(
    val id: Long, val tag: String, val title: String, val body: String, val publishedAt: String,
    val prerelease: Boolean, val assets: List<ReleaseAsset>
) {
    val apks: List<ReleaseAsset> get() = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
}

private fun AppRelease.cacheJson(): String = JSONObject().put("id", id).put("tag", tag)
    .put("title", title).put("body", body).put("publishedAt", publishedAt).put("prerelease", prerelease)
    .put("assets", JSONArray().apply { assets.forEach { asset ->
        put(JSONObject().put("id", asset.id).put("name", asset.name).put("url", asset.url)
            .put("size", asset.size).put("digest", asset.digest))
    } }).toString()

private fun cachedRelease(json: String): AppRelease {
    val item = JSONObject(json)
    val assets = item.getJSONArray("assets")
    return AppRelease(item.getLong("id"), item.getString("tag"), item.getString("title"), item.getString("body"),
        item.getString("publishedAt"), item.getBoolean("prerelease"), (0 until assets.length()).map { index ->
            val asset = assets.getJSONObject(index)
            ReleaseAsset(asset.getLong("id"), asset.getString("name"), asset.getString("url"), asset.getLong("size"), asset.getString("digest"))
        })
}

/** No account token is shipped with the app. Public Releases are the source of truth. */
internal class GitHubReleaseRepository(private val repository: String) {
    init { require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) }

    suspend fun releases(): List<AppRelease> = withContext(Dispatchers.IO) {
        val result = mutableListOf<AppRelease>()
        var page = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = open("https://api.github.com/repos/$repository/releases?per_page=100&page=$page")
            val json = try {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                checkResponse(connection)
                // Bound server input without silently truncating release notes.
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 8 * 1024 * 1024) throw IOException("发布记录过大，请稍后重试。")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                JSONArray(String(bytes, Charsets.UTF_8))
            } finally { connection.disconnect() }
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                if (item.optBoolean("draft")) continue
                val assets = item.optJSONArray("assets") ?: JSONArray()
                result += AppRelease(item.getLong("id"), item.optString("tag_name"),
                    item.optString("name").ifBlank { item.optString("tag_name") },
                    item.optString("body").takeUnless { it == "null" }.orEmpty(),
                    item.optString("published_at"), item.optBoolean("prerelease"),
                    (0 until assets.length()).mapNotNull { index ->
                        val asset = assets.getJSONObject(index)
                        if (asset.optString("state") != "uploaded") null else ReleaseAsset(
                            asset.getLong("id"), asset.getString("name"), asset.getString("browser_download_url"),
                            asset.getLong("size"), asset.optString("digest"))
                    })
            }
            if (json.length() < 100) break
            page++
            if (page > 100) throw IOException("发布记录过多，请在 GitHub 查看。")
        }
        result.distinctBy { it.id }.sortedByDescending { it.publishedAt }
    }

    suspend fun download(asset: ReleaseAsset, target: File, onProgress: suspend (Long) -> Unit) = withContext(Dispatchers.IO) {
        val expected = AppUpdatePolicy.sha256(asset.digest)
            ?: throw IOException("此安装包缺少有效的 SHA-256 校验值，请作者重新上传 Release 附件。")
        if (asset.size !in 1..(512L * 1024 * 1024)) throw IOException("安装包大小无效或超过 512 MB。")
        val source = URL(asset.url)
        if (source.protocol != "https" || source.host != "github.com" ||
            !source.path.startsWith("/$repository/releases/download/") || source.userInfo != null)
            throw IOException("安装包下载地址无效。")
        val connection = open(asset.url)
        try {
            checkResponse(connection)
            if (connection.url.protocol != "https") throw IOException("下载连接不安全。")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastUpdate = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > asset.size) throw IOException("安装包大小与发布记录不一致。")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 150) { onProgress(received); lastUpdate = now }
                    }
                    output.fd.sync()
                }
            }
            if (received != asset.size || digest.digest().hex() != expected) throw IOException("文件校验失败，请重新下载。")
            onProgress(received)
        } finally { connection.disconnect() }
    }

    private fun open(address: String) = (URL(address).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "TimeBricks-Android")
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun checkResponse(connection: HttpsURLConnection) {
        when (val code = connection.responseCode) {
            200 -> Unit
            403, 429 -> throw IOException("GitHub 暂时限制请求，请稍后重试。")
            404 -> throw IOException("未找到公开发布来源或安装包已被移除。")
            else -> throw IOException("服务器响应异常（$code），请稍后重试。")
        }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

@Suppress("DEPRECATION")
internal fun PackageInfo.updateVersionCode(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signers(): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
}

@Suppress("DEPRECATION")
internal fun installedApp(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName,
    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

/** The system installer remains the final verifier of APK cryptographic integrity and device policy. */
@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, release: AppRelease) {
    val installed = installedApp(context)
    val candidate = context.packageManager.getPackageArchiveInfo(file.path,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        ?: throw IOException("无法读取安装包，文件可能损坏。")
    AppUpdatePolicy.compatibilityError(context.packageName, candidate.packageName,
        installed.updateVersionCode(), candidate.updateVersionCode(), installed.signers(), candidate.signers(),
        Build.VERSION.SDK_INT, candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)?.let { throw IOException(it) }
    if (AppUpdatePolicy.compareVersions(candidate.versionName.orEmpty(), release.tag) != 0)
        throw IOException("安装包版本与 Release 标签不一致。")
    ZipFile(file).use { zip ->
        val nativeAbis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split('/')[1] }.toSet()
        if (nativeAbis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in nativeAbis })
            throw IOException("此安装包不支持当前设备架构，请选择其他 APK。")
    }
}

internal enum class UpdateStage { Idle, Downloading, Verifying, Ready }
internal data class UpdateState(
    val checking: Boolean = false, val historyLoading: Boolean = false,
    val releases: List<AppRelease>? = null, val available: AppRelease? = null,
    val message: String = "", val historyError: String = "",
    val stage: UpdateStage = UpdateStage.Idle, val received: Long = 0, val total: Long = 0,
    val selectedRelease: AppRelease? = null, val selectedAsset: ReleaseAsset? = null, val readyFile: File? = null
)

internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    val config = AboutConfig.from(application)
    val installed = installedApp(application)
    private val repository = GitHubReleaseRepository(config.repository)
    private val preferences = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val cachedAvailable = runCatching { cachedRelease(preferences.getString("available_release", "").orEmpty()) }
        .getOrNull()?.takeIf { !it.prerelease && (AppUpdatePolicy.compareVersions(it.tag, installed.versionName.orEmpty()) ?: -1) > 0 }
    private val mutableState = MutableStateFlow(UpdateState(available = cachedAvailable))
    val state = mutableState.asStateFlow()
    private var checkJob: Job? = null
    private var transferJob: Job? = null
    private var autoJob: Job? = null
    val busy: Boolean get() = state.value.stage in setOf(UpdateStage.Downloading, UpdateStage.Verifying)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            File(application.cacheDir, "app-updates").listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun autoCheck() {
        if (autoJob != null) return
        autoJob = viewModelScope.launch {
            delay(8_000)
            if (AppUpdatePolicy.shouldAutoCheck(System.currentTimeMillis(), preferences.getLong("last_attempt", 0))) check(false)
        }
    }

    fun check(manual: Boolean = true) {
        if (checkJob?.isActive == true) return
        if (manual) autoJob?.cancel()
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        mutableState.update { it.copy(checking = true, message = if (manual) "" else it.message) }
        checkJob = viewModelScope.launch {
            try {
                val releases = repository.releases()
                val known = AppUpdatePolicy.version(installed.versionName.orEmpty()) != null
                val newer = releases.filter { !it.prerelease &&
                    (AppUpdatePolicy.compareVersions(it.tag, installed.versionName.orEmpty()) ?: -1) > 0 }
                    .maxWithOrNull { a, b -> AppUpdatePolicy.compareVersions(a.tag, b.tag) ?: 0 }
                val message = when {
                    !known -> "当前版本名称无法比较，请在版本记录中查看发布说明。"
                    newer != null -> "发现新版本 ${newer.tag}"
                    releases.isEmpty() -> "尚无公开发布版本。"
                    releases.none { !it.prerelease && AppUpdatePolicy.version(it.tag) != null } -> "暂无可比较的正式版本，请查看版本记录。"
                    else -> "已是最新版本。"
                }
                preferences.edit().apply {
                    if (newer == null) remove("available_release") else putString("available_release", newer.cacheJson())
                }.apply()
                mutableState.update { it.copy(releases = releases, available = newer, message = if (manual) message else it.message) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (manual) report(error.userMessage()) }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun loadHistory() {
        if (state.value.historyLoading) return
        mutableState.update { it.copy(historyLoading = true, historyError = "") }
        viewModelScope.launch {
            try { val releases = repository.releases(); mutableState.update { it.copy(releases = releases) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(historyError = error.userMessage()) } }
            finally { mutableState.update { it.copy(historyLoading = false) } }
        }
    }

    fun download(release: AppRelease, asset: ReleaseAsset) {
        if (busy) return
        if (asset !in release.apks || release.prerelease ||
            (AppUpdatePolicy.compareVersions(release.tag, installed.versionName.orEmpty()) ?: -1) <= 0) {
            report("此版本不能作为当前应用的更新安装。"); return
        }
        val oldFile = state.value.readyFile
        mutableState.update { it.copy(stage = UpdateStage.Downloading, selectedRelease = release, selectedAsset = asset,
            readyFile = null, received = 0, total = asset.size, message = "") }
        transferJob = viewModelScope.launch {
            var file: File? = null
            try {
                val ready = withContext(Dispatchers.IO) {
                    oldFile?.delete()
                    val directory = File(getApplication<Application>().cacheDir, "app-updates")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建下载目录。")
                    if (directory.usableSpace < asset.size + 16 * 1024 * 1024) throw IOException("存储空间不足。")
                    val target = File(directory, "${UUID.randomUUID()}.apk").also { file = it }
                    repository.download(asset, target) { received ->
                        withContext(Dispatchers.Main) { mutableState.update { it.copy(received = received) } }
                    }
                    withContext(Dispatchers.Main) { mutableState.update { it.copy(stage = UpdateStage.Verifying) } }
                    verifyUpdateApk(getApplication(), target, release)
                    currentCoroutineContext().ensureActive()
                    target
                }
                mutableState.update { it.copy(stage = UpdateStage.Ready, readyFile = ready, message = "校验通过，可以安装。") }
            } catch (cancelled: CancellationException) { file?.delete(); throw cancelled }
            catch (error: Exception) {
                file?.delete()
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(stage = UpdateStage.Idle, readyFile = null, message = error.userMessage()) }
            }
        }
    }

    fun cancelDownload() {
        transferJob?.cancel()
        mutableState.update { it.copy(stage = UpdateStage.Idle, readyFile = null, message = "已取消下载。") }
    }

    fun report(message: String) { mutableState.update { it.copy(message = message) } }

    fun installerIntent(): Intent {
        val file = state.value.readyFile?.takeIf { it.isFile } ?: throw IOException("安装包已失效，请重新下载。")
        val uri = FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun Exception.userMessage(): String = when (this) {
    is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接 GitHub，请检查网络后重试。"
    is java.net.SocketTimeoutException -> "网络连接超时，请重试。"
    is javax.net.ssl.SSLException -> "安全连接失败，请检查设备时间或网络。"
    is SecurityException -> "系统限制了此操作，请检查应用权限。"
    is IOException -> localizedMessage ?: "下载或读取失败，请重试。"
    else -> "无法读取更新信息，请稍后重试。"
}
