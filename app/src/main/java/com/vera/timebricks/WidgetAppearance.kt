package com.vera.timebricks

import android.widget.RemoteViews
import android.os.Build
import android.content.res.ColorStateList

// System mode leaves XML colors untouched: the host resolves values/values-night when
// reapplying RemoteViews. Explicit app modes override every neutral surface and label.
internal object WidgetAppearance {
    fun headerLayout(mode: String, narrow: Boolean): Int = when (mode) {
        "light" -> if (narrow) R.layout.widget_schedule_narrow_light else R.layout.widget_schedule_light
        "dark" -> if (narrow) R.layout.widget_schedule_narrow_dark else R.layout.widget_schedule_dark
        else -> if (narrow) R.layout.widget_schedule_narrow else R.layout.widget_schedule
    }
    fun rowLayout(mode: String): Int = when (mode) {
        "light" -> R.layout.widget_event_light
        "dark" -> R.layout.widget_event_dark
        else -> R.layout.widget_event
    }
    private fun text(views: RemoteViews, id: Int, mode: String, secondary: Boolean = false) {
        val light = if (secondary) 0xFF525F69.toInt() else 0xFF20272D.toInt()
        val dark = if (secondary) 0xFFA6A6A6.toInt() else 0xFFF2F2F2.toInt()
        if (mode == "system" && Build.VERSION.SDK_INT >= 31) views.setColorInt(id, "setTextColor", light, dark)
        else fixed(mode)?.let { views.setTextColor(id, if (secondary) it.secondary else it.text) }
    }
    private data class Palette(val text: Int, val secondary: Int)
    private fun fixed(mode: String): Palette? = when (mode) {
        "light" -> Palette(0xFF20272D.toInt(), 0xFF525F69.toInt())
        "dark" -> Palette(0xFFF2F2F2.toInt(), 0xFFA6A6A6.toInt())
        else -> null
    }
    fun todayOutline(mode: String): Int = when (mode) {
        "light" -> R.drawable.widget_today_outline_light
        "dark" -> R.drawable.widget_today_outline_dark
        else -> R.drawable.widget_today_outline
    }
    fun header(views: RemoteViews, mode: String) {
        views.setInt(R.id.widget_root, "setBackgroundResource", when (mode) {
            "light" -> R.drawable.widget_background_light
            "dark" -> R.drawable.widget_background_dark
            else -> R.drawable.widget_background
        })
        if (mode == "system" && Build.VERSION.SDK_INT >= 31) views.setColorStateList(R.id.widget_root,
            "setBackgroundTintList", ColorStateList.valueOf(0xFFF5F6F7.toInt()), ColorStateList.valueOf(0xFF070707.toInt()))
        intArrayOf(R.id.widget_date, R.id.widget_mon, R.id.widget_tue, R.id.widget_wed,
            R.id.widget_thu, R.id.widget_fri, R.id.widget_sat, R.id.widget_sun)
            .forEach { text(views, it, mode) }
        intArrayOf(R.id.widget_empty, R.id.widget_page_label, R.id.widget_page_previous, R.id.widget_page_next,
            R.id.widget_label_mon, R.id.widget_label_tue, R.id.widget_label_wed, R.id.widget_label_thu,
            R.id.widget_label_fri, R.id.widget_label_sat, R.id.widget_label_sun)
            .forEach { text(views, it, mode, true) }
        intArrayOf(R.id.widget_today, R.id.widget_previous, R.id.widget_next, R.id.widget_add, R.id.widget_refresh)
            .forEach {
                if (mode == "system" && Build.VERSION.SDK_INT >= 31) views.setColorInt(it, "setColorFilter", 0xFF525F69.toInt(), 0xFFA6A6A6.toInt())
                else fixed(mode)?.let { palette -> views.setInt(it, "setColorFilter", palette.secondary) }
            }
    }
    fun row(views: RemoteViews, mode: String) {
        views.setInt(R.id.widget_event_card, "setBackgroundResource", when (mode) {
            "light" -> R.drawable.widget_card_light
            "dark" -> R.drawable.widget_card_dark
            else -> R.drawable.widget_card
        })
        if (mode == "system" && Build.VERSION.SDK_INT >= 31) views.setColorStateList(R.id.widget_event_card,
            "setBackgroundTintList", ColorStateList.valueOf(0xFFE7EAED.toInt()), ColorStateList.valueOf(0xFF1A1A1A.toInt()))
        intArrayOf(R.id.widget_event_title, R.id.widget_event_time).forEach { text(views, it, mode) }
        intArrayOf(R.id.widget_event_duration, R.id.widget_event_end).forEach { text(views, it, mode, true) }
    }
}
