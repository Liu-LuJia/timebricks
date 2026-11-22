package com.vera.timebricks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val LocalAppUpdater = staticCompositionLocalOf<AppUpdateModel> { error("App updater is not provided") }

private data class AppFeature(val name: String, val description: String)
private val implementedFeatures = listOf(
    AppFeature("日程规划", "在「规划」页选择日期和分类，在时间格中安排事件。可精确编辑起止时间，支持重叠事件及撤销、重做。"),
    AppFeature("分类与模板", "在设置中管理主分类、子分类及颜色。把常用安排保存为模板，在模板管理中应用到日期；归档后仍保留历史。"),
    AppFeature("重复事件", "在设置的「重复事件」中创建规则，管理暂停与恢复，并对单次安排作例外调整。"),
    AppFeature("日程与统计", "在「日程」页浏览安排，切换统计视图按日期范围查看时间分布，并导出日程。"),
    AppFeature("提醒与桌面组件", "在「通知提醒」中设置提醒和提前量，也可启用状态栏日程；从系统桌面添加时间积木组件。提醒、悬浮窗和后台运行受系统权限及省电策略影响。"),
    AppFeature("备份与恢复", "在「数据管理」中导出、导入备份，或选择文件夹开启自动本地备份。导入会完整替换当前数据，请先备份。"),
    AppFeature("外观设置", "在「外观主题」中设置深浅模式、主题色、字号、间距和圆角。设置自动保存。"),
    AppFeature("应用更新", "在「关于时间积木」中检查 GitHub 正式发布版本，查看说明并选择 APK 下载。启动后每天最多静默检查一次，不自动弹窗或下载。安装需包名、签名一致且版本号递增，由系统完成覆盖安装。")
)

@Composable
internal fun AboutRow(title: String, value: String = "", onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = BricksDimensions.listRow)
        .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (value.isNotEmpty()) Text(value, Modifier.weight(1f).padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.End)
        if (onClick != null) ChevronIcon(Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val model = LocalAppUpdater.current
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("about") }
    var featureIndex by rememberSaveable { mutableStateOf(0) }
    var releaseId by rememberSaveable { mutableStateOf(0L) }
    var releaseParent by rememberSaveable { mutableStateOf("history") }
    var confirmation by remember { mutableStateOf<Pair<AppRelease, ReleaseAsset>?>(null) }
    var contactMessage by remember { mutableStateOf("") }
    var permissionPrompt by remember { mutableStateOf(false) }
    val release = state.releases?.find { it.id == releaseId }
        ?: state.selectedRelease?.takeIf { it.id == releaseId }
        ?: state.available?.takeIf { it.id == releaseId }
    val title = when (page) {
        "features" -> "功能介绍"; "feature" -> implementedFeatures[featureIndex].name
        "history" -> "版本记录"; "release" -> release?.tag ?: "版本详情"
        "update" -> "检查更新"; else -> "关于${context.applicationInfo.loadLabel(context.packageManager)}"
    }
    fun back() {
        when (page) {
            "about" -> onBack()
            "feature" -> page = "features"
            "release" -> page = releaseParent
            else -> page = "about"
        }
    }
    BackHandler { back() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.report(if (context.packageManager.canRequestPackageInstalls()) "已允许安装，请点击安装更新。"
            else "未允许安装，安装包已保留，可稍后重试。")
    }
    val installerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // ACTION_VIEW result codes are not reliable across installers. Never claim success based on a result code.
        model.report("已返回应用。如未完成安装，可再次点击安装更新。")
    }
    fun install() {
        if (!context.packageManager.canRequestPackageInstalls()) { permissionPrompt = true; return }
        try { installerLauncher.launch(model.installerIntent()) }
        catch (_: Exception) { model.report("无法打开系统安装界面，请检查安装限制或重新下载。") }
    }
    fun viewRelease(item: AppRelease, parent: String) { releaseId = item.id; releaseParent = parent; page = "release" }
    Column(Modifier.fillMaxSize().padding(horizontal = BricksDimensions.pageInset)) {
        BricksHeader(title, true, { back() })
        key(page, releaseId, featureIndex) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BricksDimensions.gap * 2)) {
                when (page) {
                    "about" -> {
                        val icon = remember(context.applicationContext) {
                            context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap()
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(icon, contentDescription = "应用图标", modifier = Modifier.size(56.dp))
                            Column(Modifier.weight(1f)) {
                                Text(context.applicationInfo.loadLabel(context.packageManager).toString(),
                                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                Text("${model.installed.versionName.orEmpty()} (${model.installed.updateVersionCode()})",
                                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SettingsGroup {
                            AboutRow("检查更新", when {
                                model.busy -> "下载中"
                                state.stage == UpdateStage.Ready -> "待安装"
                                state.checking -> "检查中"
                                state.available != null -> "新版本"
                                else -> ""
                            }, { page = "update"; if (!model.busy && state.stage != UpdateStage.Ready) model.check() })
                            AboutRow("功能介绍", onClick = { page = "features" })
                            AboutRow("版本记录", onClick = { page = "history"; model.loadHistory() })
                        }
                        SettingsGroup {
                            AboutRow("作者", model.config.author.ifBlank { "未配置" })
                            AboutRow("反馈邮箱", model.config.email.ifBlank { "未配置" },
                                onClick = if (model.config.email.isBlank()) null else ({
                                    try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", model.config.email, null))) }
                                    catch (_: Exception) { contactMessage = "未找到邮件应用。邮箱：${model.config.email}" }
                                }))
                            AboutRow("QQ 交流群", model.config.qqGroup, {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("QQ 交流群", model.config.qqGroup))
                                contactMessage = "群号已复制，可在 QQ 中搜索加入。"
                            })
                        }
                        if (contactMessage.isNotEmpty()) AboutText(contactMessage)
                    }
                    "features" -> SettingsGroup {
                        implementedFeatures.forEachIndexed { index, feature ->
                            AboutRow(feature.name, onClick = { featureIndex = index; page = "feature" })
                        }
                    }
                    "feature" -> SettingsGroup { AboutText(implementedFeatures[featureIndex].description) }
                    "history" -> {
                        if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.historyError.isNotEmpty()) AboutText(state.historyError)
                        if (state.releases?.isEmpty() == true && !state.historyLoading) AboutText("尚无公开发布记录。")
                        state.releases.orEmpty().forEach { item ->
                            SettingsGroup {
                                AboutRow(item.tag, releaseDate(item.publishedAt), { viewRelease(item, "history") })
                                Text(item.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "暂无更新说明" },
                                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                                    maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        SettingsGroup { AboutRow(if (state.historyLoading) "加载中" else "刷新", onClick = { model.loadHistory() }) }
                    }
                    "release" -> if (release == null) {
                        AboutText("请重新打开版本记录。")
                    } else {
                        ReleaseNotes(release)
                        if (!release.prerelease && (AppUpdatePolicy.compareVersions(release.tag, model.installed.versionName.orEmpty()) ?: -1) > 0) {
                            if (release.apks.isEmpty()) AboutText("此版本尚未提供 APK。")
                            else SettingsGroup { release.apks.forEach { asset ->
                                AboutRow(asset.name, megabytes(asset.size), { if (!model.busy) confirmation = release to asset })
                            } }
                        }
                    }
                    "update" -> {
                        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        SettingsGroup { AboutRow(if (state.checking) "正在检查" else "检查更新", onClick = { model.check() }) }
                        if (state.available != null) {
                            val available = state.available!!
                            SettingsGroup { AboutRow("更新说明", available.tag, { viewRelease(available, "update") }) }
                            ReleaseNotes(available)
                            if (available.apks.isEmpty()) AboutText("此版本尚未提供 APK。")
                            else if (!model.busy) SettingsGroup { available.apks.forEach { asset ->
                                AboutRow(asset.name, megabytes(asset.size), { confirmation = available to asset })
                            } }
                        }
                        if (model.busy) SettingsGroup {
                            AboutText(state.selectedAsset?.name.orEmpty())
                            if (state.stage == UpdateStage.Verifying) {
                                AboutText("正在校验")
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            } else {
                                LinearProgressIndicator(progress = { (state.received.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                                AboutText("${megabytes(state.received)} / ${megabytes(state.total)}")
                            }
                            AboutRow("取消下载", onClick = model::cancelDownload)
                        }
                        if (state.stage == UpdateStage.Ready) SettingsGroup {
                            AboutRow("安装更新", state.selectedRelease?.tag.orEmpty(), { install() }, Modifier.testTag("install-update"))
                        }
                        if (state.message.isNotEmpty()) AboutText(state.message)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    confirmation?.let { (item, asset) ->
        CompactBricksDialog(onDismissRequest = { confirmation = null }, title = { Text("下载更新？") },
            text = { Text("${item.tag}\n${asset.name}\n${megabytes(asset.size)}\n将使用当前网络下载，可能消耗移动数据。") },
            confirmButton = { TextButton(onClick = { confirmation = null; page = "update"; model.download(item, asset) }) { Text("下载") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
    if (permissionPrompt) CompactBricksDialog(onDismissRequest = { permissionPrompt = false },
        title = { Text("允许安装更新") }, text = { Text("需要在系统设置中允许时间积木安装应用。返回后点击安装更新；取消会保留已下载文件。") },
        confirmButton = { TextButton(onClick = {
            permissionPrompt = false
            try { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { model.report("无法打开安装权限设置，请在系统应用设置中允许安装未知应用。") }
        }) { Text("去设置") } }, dismissButton = { TextButton(onClick = { permissionPrompt = false }) { Text("取消") } })
}

@Composable
private fun AboutText(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurface, lineHeight = 23.sp)
}

@Composable
private fun ReleaseNotes(release: AppRelease) {
    SettingsGroup {
        AboutRow(release.tag, releaseDate(release.publishedAt))
        if (release.prerelease) AboutText("预发布版本")
        AboutText(release.body.ifBlank { "此版本未填写更新说明。" })
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
private fun releaseDate(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}.getOrDefault("日期未知")
