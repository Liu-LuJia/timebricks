package com.vera.timebricks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal enum class ScheduleReadStatus(val title: String, val explanation: String) {
    NOT_INSTALLED("未检测到安装", "安装 SelfPact 后，可从 SelfPact 发起读取时间积木日程的授权请求。"),
    NOT_GRANTED("未授权", "请在 SelfPact 中发起读取日程的请求，并在时间积木的授权提示中确认。"),
    GRANTED("已授权 · 只读", "可读取全部日期的日程、分类和时间；不能修改数据。设备重启后需重新授权。"),
    PARTIAL("部分授权有效", "部分日程接口仍可读取。请在来源应用中重新申请完整授权，或撤销现有授权。"),
    EXPIRED("需重新授权", "保留了授权记录，但当前读取权限已失效。请在来源应用中重新发起授权。"),
    SIGNATURE("同签名访问", "此应用持有同签名读取权限，可直接读取日程；本页无法关闭这类访问。"),
    UNKNOWN("无法确认状态", "应用可能已卸载，或系统暂时无法查询。不会将历史记录视为有效授权。")
}

internal data class ScheduleAccessEntry(
    val packageName: String,
    val label: String,
    val status: ScheduleReadStatus,
    val hasManualGrant: Boolean
)

internal fun scheduleReadStatus(signature: Boolean, v1: Boolean, v2: Boolean, recorded: Boolean): ScheduleReadStatus = when {
    signature -> ScheduleReadStatus.SIGNATURE
    v1 && v2 -> ScheduleReadStatus.GRANTED
    v1 || v2 -> ScheduleReadStatus.PARTIAL
    recorded -> ScheduleReadStatus.EXPIRED
    else -> ScheduleReadStatus.NOT_GRANTED
}

/** Preferences are history, not proof of access: ask Android for each current UID and API root. */
internal fun readScheduleAccessEntries(context: Context): List<ScheduleAccessEntry> {
    val manager = context.packageManager
    val permission = "${context.packageName}.permission.READ_SCHEDULE"
    val manual = ScheduleAccess.packages(context)
    // Only visible apps are returned; do not request broad installed-app visibility.
    val signatureApps = manager.getPackagesHoldingPermissions(arrayOf(permission), 0)
        .map { it.packageName }
    val selfPact = "com.vera.selfpact"
    return (manual + signatureApps + selfPact).filter { it != context.packageName }.sorted().map { target ->
        val info = try { manager.getApplicationInfo(target, 0) }
        catch (_: PackageManager.NameNotFoundException) { null }
        catch (_: SecurityException) { null }
        val label = info?.let { runCatching { manager.getApplicationLabel(it).toString().take(80) }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: if (target == selfPact) "SelfPact" else target
        val status = if (info == null) {
            if (target == selfPact && target !in manual) ScheduleReadStatus.NOT_INSTALLED else ScheduleReadStatus.UNKNOWN
        } else try {
            scheduleReadStatus(
                signature = manager.checkPermission(permission, target) == PackageManager.PERMISSION_GRANTED,
                v1 = context.checkUriPermission(ScheduleAccess.root(context, 1), -1, info.uid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED,
                v2 = context.checkUriPermission(ScheduleAccess.root(context, 2), -1, info.uid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED,
                recorded = target in manual
            )
        } catch (_: SecurityException) { ScheduleReadStatus.UNKNOWN }
        ScheduleAccessEntry(target, label, status, target in manual)
    }
}
