package com.vera.timebricks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager

internal object ScheduleAccess {
    fun root(context: Context, version: Int = 1): Uri = Uri.parse("content://${context.packageName}.schedule/v$version/")
    private fun prefs(context: Context) = context.getSharedPreferences("schedule-read-grants", Context.MODE_PRIVATE)
    fun packages(context: Context): Set<String> = prefs(context).getStringSet("packages", emptySet())!!.toSet()
    fun grant(context: Context, target: String) {
        // Explicit URI grants last until reboot/revocation. Never grant WRITE or PERSISTABLE.
        (1..2).forEach { version -> context.grantUriPermission(target, root(context, version), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) }
        prefs(context).edit().putStringSet("packages", packages(context) + target).apply()
    }
    fun revoke(context: Context, target: String) {
        (1..2).forEach { version -> context.revokeUriPermission(target, root(context, version), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs(context).edit().putStringSet("packages", packages(context) - target).apply()
    }
}

/** Calling package comes from Android's activity result token, never from Intent extras. */
class ScheduleAccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val target = callingPackage
        if (target == null || target == packageName) { finish(); return }
        val info = try { packageManager.getApplicationInfo(target, 0) }
        catch (_: Exception) { finish(); return }
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val label = packageManager.getApplicationLabel(info).toString().take(80)
        val dialog = AlertDialog.Builder(this)
            .setTitle("允许读取时间积木日程？")
            .setMessage("应用：$label\n包名：$target\n\n允许读取全部日期的日程、分类和时间；不能修改数据。此授权在设备重启后失效，也可在时间积木「设置 → 数据管理 → 日程读取授权」中撤销。")
            .setPositiveButton("允许只读访问") { _, _ ->
                try { ScheduleAccess.grant(this, target); setResult(RESULT_OK) }
                catch (_: Exception) { setResult(RESULT_CANCELED) }
                finish()
            }
            .setNegativeButton("拒绝") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 31) dialog.window?.setHideOverlayWindows(true)
    }
}
