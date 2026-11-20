package com.vera.timebricks

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class WidgetEvent(val id: String, val title: String, val color: Int, val start: Int, val end: Int, val seriesKey: String? = null)
internal data class WidgetSnapshot(val days: Map<Long, List<WidgetEvent>>, val themeMode: String)
internal data class WidgetLegacyRows(val day: Long, val events: List<WidgetEvent>, val themeMode: String)
internal data class WidgetOpenRequest(val day: Long, val eventId: String?, val destination: String = "planning")

// Pure date/page policy also used by the JVM regression checks.
internal object WidgetPolicy {
    const val PAGE_SIZE = 60 // Bound the Binder payload; unusually busy days retain explicit paging.
    val minDay: Long = LocalDate.of(1, 1, 1).toEpochDay()
    val maxDay: Long = LocalDate.of(9999, 12, 31).toEpochDay()
    fun validDay(day: Long) = day in minDay..maxDay
    fun shift(day: Long, delta: Int) = (day + delta).coerceIn(minDay, maxDay)
    fun weekStart(day: Long) = day - (LocalDate.ofEpochDay(day).dayOfWeek.value - 1)
    fun page(page: Int, count: Int) = page.coerceIn(0, ((count - 1).coerceAtLeast(0) / PAGE_SIZE))
    fun clock(minute: Int) = scheduleClock(minute)
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "${minutes}分钟"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes / 60}小时${minutes % 60}分"
    }
    fun focusIndex(events: List<WidgetEvent>, minute: Int): Int {
        if (events.isEmpty()) return 0
        // Most recently started active event wins; equal starts retain stable sorted order.
        val active = events.indices.filter { events[it].start <= minute && minute < events[it].end }
        return active.maxByOrNull { events[it].start }
            ?: events.indexOfFirst { it.start > minute }.takeIf { it >= 0 } ?: events.lastIndex
    }
    fun fromIndex(events: List<WidgetEvent>, index: Int): List<WidgetEvent> =
        if (events.isEmpty()) events else events.drop(index.coerceIn(events.indices)) + events.take(index.coerceIn(events.indices))
    fun nextUpdate(now: java.time.Instant, zone: ZoneId, events: List<WidgetEvent>): Long {
        val date = now.atZone(zone).toLocalDate().atStartOfDay()
        val boundaries = events.flatMap { listOf(it.start, it.end) }.distinct().flatMap {
            val local = date.plusMinutes(it.toLong())
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.isEmpty()) listOf(local.atZone(zone).toInstant().toEpochMilli())
            else offsets.map { offset -> local.toInstant(offset).toEpochMilli() }
        }
        val transition = zone.rules.nextTransition(now)?.instant?.toEpochMilli()
        return (boundaries + listOfNotNull(transition, nextMidnight(now, zone)))
            .filter { it > now.toEpochMilli() }.min()
    }
    fun nextMidnight(now: java.time.Instant, zone: ZoneId): Long =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

internal object ScheduleWidgets {
    const val OPEN = "com.vera.timebricks.widget.OPEN"
    const val DAY = "widget_day"
    const val EVENT = "widget_event"
    private const val ACTION = "com.vera.timebricks.widget.ACTION"
    private const val REFRESH = "com.vera.timebricks.widget.REFRESH"
    private const val CHECK_DAY = "com.vera.timebricks.widget.CHECK_DAY"
    private const val MIDNIGHT = "com.vera.timebricks.widget.MIDNIGHT"
    private const val OP = "widget_op"
    private const val PAGE = "widget_page"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val refreshQueued = AtomicBoolean(false)
    fun accepts(action: String?) = action in setOf(ACTION, REFRESH, CHECK_DAY, MIDNIGHT,
        AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_ENABLED,
        AppWidgetManager.ACTION_APPWIDGET_DISABLED, AppWidgetManager.ACTION_APPWIDGET_DELETED,
        AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, AppWidgetManager.ACTION_APPWIDGET_RESTORED,
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_DATE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)
    private fun prefs(context: Context) = context.getSharedPreferences("schedule-widgets-v1", Context.MODE_PRIVATE)
    private fun ids(context: Context) = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, TimeBricksWidgetProvider::class.java))

    fun openRequest(intent: Intent?): WidgetOpenRequest? {
        if (intent?.action != OPEN || !intent.hasExtra(DAY)) return null
        val day = intent.getLongExtra(DAY, Long.MIN_VALUE)
        return if (WidgetPolicy.validDay(day)) WidgetOpenRequest(day, intent.getStringExtra(EVENT), intent.getStringExtra("widget_destination").takeIf { it in listOf("create", "schedule") } ?: "planning") else null
    }

    // Called only after a durable calendar commit. Rapid save/undo bursts collapse into one broadcast.
    fun dataChanged(context: Context) {
        val app = context.applicationContext
        if (refreshQueued.compareAndSet(false, true)) executor.schedule({
            refreshQueued.set(false)
            runCatching { app.sendBroadcast(Intent(app, TimeBricksWidgetProvider::class.java).setAction(REFRESH)) }
        }, 250, TimeUnit.MILLISECONDS)
    }

    fun checkDay(context: Context) {
        runCatching { context.sendBroadcast(Intent(context, TimeBricksWidgetProvider::class.java).setAction(CHECK_DAY)) }
    }

    fun dispatch(context: Context, intent: Intent, finish: () -> Unit) {
        executor.execute {
            try { handle(context.applicationContext, intent) }
            catch (error: Exception) { android.util.Log.e("TimeBricksWidget", "Widget update failed", error) }
            finally { finish() }
        }
    }

    private fun selectedDay(context: Context, id: Int, today: Long): Long =
        prefs(context).getLong("day:$id", today).takeIf(WidgetPolicy::validDay) ?: today

    private fun handle(context: Context, intent: Intent) {
        val all = ids(context)
        val p = prefs(context)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_DELETED) {
            val editor = p.edit()
            (intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf()).forEach { id ->
                editor.remove("day:$id").remove("page:$id")
            }
            editor.commit()
        }
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_RESTORED) {
            val old = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: intArrayOf()
            val new = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf()
            val editor = p.edit()
            old.zip(new).forEach { (a, b) ->
                if (p.contains("day:$a")) editor.putLong("day:$b", p.getLong("day:$a", 0))
                editor.remove("day:$a").remove("page:$a")
            }
            editor.commit()
            val manager = AppWidgetManager.getInstance(context)
            new.forEach { id -> manager.updateAppWidgetOptions(id, Bundle().apply {
                putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true)
            }) }
        }
        if (all.isEmpty()) { scheduleUpdate(context, false); return }
        val now = java.time.Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate().toEpochDay()
        val dayChanged = p.getLong("lastToday", Long.MIN_VALUE) != today
        val systemNight = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        var targets = all
        var resetScroll = false
        if (intent.action == ACTION) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id !in all) return
            val editor = p.edit()
            when (intent.getStringExtra(OP)) {
                "date" -> {
                    val day = intent.getLongExtra(DAY, Long.MIN_VALUE)
                    if (!WidgetPolicy.validDay(day)) return
                    if (day == today) editor.remove("day:$id") else editor.putLong("day:$id", day)
                    editor.putInt("page:$id", 0)
                }
                "today" -> editor.remove("day:$id").putInt("page:$id", 0)
                "refresh" -> Unit
                "page" -> editor.putInt("page:$id", intent.getIntExtra(PAGE, 0).coerceAtLeast(0))
                else -> return
            }
            editor.commit()
            targets = if (dayChanged) all else intArrayOf(id)
            resetScroll = intent.getStringExtra(OP) != "refresh"
        } else if (intent.action == CHECK_DAY && !dayChanged && !p.getBoolean("lastFailure", false) && p.getInt("lastSystemNight", -1) == systemNight && p.getLong("nextWidgetAt", 0) > now.toEpochMilli()) return
        else if (intent.action == AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED && !dayChanged) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            targets = if (id in all) intArrayOf(id) else all
        }
        if (dayChanged) {
            val editor = p.edit().putLong("lastToday", today)
            all.filter { !p.contains("day:$it") }.forEach { editor.putInt("page:$it", 0) }
            editor.commit()
        }
        val days = targets.associateWith { selectedDay(context, it, today) }
        // One disk snapshot, and only the distinct days actually displayed by active widgets.
        val showsToday = all.any { selectedDay(context, it, today) == today }
        val wantedDays = days.values.toSet() + if (showsToday) setOf(today) else emptySet()
        val snapshots = runCatching { readWidgetSnapshot(context as Application, wantedDays) }
        val themeMode = snapshots.getOrNull()?.themeMode ?: p.getString("lastTheme", "dark")!!
        if (snapshots.isSuccess) p.edit().putString("lastTheme", themeMode).commit()
        var failed = snapshots.isFailure
        targets.forEach { id ->
            runCatching {
                val day = days.getValue(id)
                render(context, id, day, today, snapshots.getOrNull()?.days?.get(day).orEmpty(), themeMode,
                    snapshots.isFailure, resetScroll || dayChanged, now,
                    intent.getStringExtra(OP) == "refresh", intent.getStringExtra(OP) == "page")
            }.onFailure { failed = true; android.util.Log.e("TimeBricksWidget", "Cannot render widget $id", it) }
        }
        p.edit().putBoolean("lastFailure", failed).putInt("lastSystemNight", systemNight).commit()
        val next = WidgetPolicy.nextUpdate(now, zone, if (showsToday) snapshots.getOrNull()?.days?.get(today).orEmpty() else emptyList())
        p.edit().putLong("nextWidgetAt", next).commit()
        scheduleUpdate(context, true, next)
        if (intent.action == ACTION && intent.getStringExtra(OP) == "refresh") {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, if (failed) "刷新失败，请打开应用重试" else "已刷新", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun command(context: Context, id: Int, op: String, day: Long = 0, page: Int = 0): PendingIntent {
        val intent = Intent(context, TimeBricksWidgetProvider::class.java).setAction(ACTION)
            .setData(Uri.parse("timebricks-widget://action/$id/$op/$day/$page"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(OP, op).putExtra(DAY, day).putExtra(PAGE, page)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun launch(context: Context, id: Int, day: Long, collection: Boolean, destination: String = "planning"): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN)
            .setData(Uri.parse("timebricks-widget://open/$id/$day/${if (collection) "list" else destination}"))
            .putExtra(DAY, day).putExtra("widget_destination", destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val mutable = if (collection && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else
            if (collection) 0 else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    @Suppress("DEPRECATION")
    private fun render(context: Context, id: Int, day: Long, today: Long, rawEvents: List<WidgetEvent>, themeMode: String, failed: Boolean, resetScroll: Boolean,
                       now: java.time.Instant, manualRefresh: Boolean, manualPage: Boolean) {
        val p = prefs(context)
        val localNow = now.atZone(ZoneId.systemDefault())
        val events = if (day == today) WidgetPolicy.fromIndex(rawEvents, WidgetPolicy.focusIndex(rawEvents, localNow.hour * 60 + localNow.minute)) else rawEvents
        val firstKey = "$day:${events.firstOrNull()?.id.orEmpty()}"
        val focusChanged = !failed && firstKey != p.getString("first:$id", null)
        val focus = !manualPage && (manualRefresh || focusChanged)
        if (focus) p.edit().putInt("page:$id", 0).commit()
        if (!failed) p.edit().putString("first:$id", firstKey).commit()
        val page = WidgetPolicy.page(p.getInt("page:$id", 0), events.size)
        if (p.getInt("page:$id", 0) != page) p.edit().putInt("page:$id", page).commit()
        val visible = events.drop(page * WidgetPolicy.PAGE_SIZE).take(WidgetPolicy.PAGE_SIZE)
        val manager = AppWidgetManager.getInstance(context)
        fun layout(week: Boolean, narrow: Boolean = false): RemoteViews {
            val views = RemoteViews(context.packageName, WidgetAppearance.headerLayout(themeMode, narrow))
            WidgetAppearance.header(views, themeMode)
            val date = LocalDate.ofEpochDay(day)
            views.setTextViewText(R.id.widget_date, if (!narrow) listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")[date.monthValue - 1] else date.format(DateTimeFormatter.ofPattern("M/d", Locale.ROOT)))
            views.setOnClickPendingIntent(R.id.widget_date, launch(context, id, day, false))
            views.setOnClickPendingIntent(R.id.widget_today, command(context, id, "today"))
            views.setContentDescription(R.id.widget_today, "回到今天")
            views.setOnClickPendingIntent(R.id.widget_add, launch(context, id, day, false, "create"))
            views.setOnClickPendingIntent(R.id.widget_refresh, command(context, id, "refresh"))
            views.setContentDescription(R.id.widget_date, "${date.year}年${date.monthValue}月${date.dayOfMonth}日，打开规划")
            views.setOnClickPendingIntent(R.id.widget_previous, command(context, id, "date", WidgetPolicy.shift(day, if (week) -7 else -1)))
            views.setOnClickPendingIntent(R.id.widget_next, command(context, id, "date", WidgetPolicy.shift(day, if (week) 7 else 1)))
            views.setContentDescription(R.id.widget_previous, if (week) "上一周" else "前一天")
            views.setContentDescription(R.id.widget_next, if (week) "下一周" else "后一天")
            views.setViewVisibility(R.id.widget_week, if (week) View.VISIBLE else View.GONE)
            val dateIds = intArrayOf(R.id.widget_mon, R.id.widget_tue, R.id.widget_wed, R.id.widget_thu, R.id.widget_fri, R.id.widget_sat, R.id.widget_sun)
            val monday = WidgetPolicy.weekStart(day)
            dateIds.forEachIndexed { index, viewId ->
                val d = monday + index
                val valid = WidgetPolicy.validDay(d)
                views.setTextViewText(viewId, if (valid) "${LocalDate.ofEpochDay(d).dayOfMonth}" else "")
                views.setInt(viewId, "setBackgroundResource", when (d) { day -> R.drawable.widget_selected; today -> WidgetAppearance.todayOutline(themeMode); else -> R.drawable.widget_clear })
                if (d == day) views.setTextColor(viewId, android.graphics.Color.WHITE)
                if (valid) views.setOnClickPendingIntent(viewId, command(context, id, "date", d))
                views.setContentDescription(viewId, if (valid) "${LocalDate.ofEpochDay(d)}${if (d == day) "，已选中" else ""}" else "")
            }
            views.setTextViewText(R.id.widget_empty, if (failed) "读取失败，点击打开应用重试" else "当天暂无日程")
            views.setOnClickPendingIntent(R.id.widget_empty, launch(context, id, day, false))
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setPendingIntentTemplate(R.id.widget_list, launch(context, id, day, true))
            if (Build.VERSION.SDK_INT >= 31) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(false)
                visible.forEachIndexed { index, event -> items.addItem(index.toLong(), row(context, day, event, themeMode)) }
                views.setRemoteAdapter(R.id.widget_list, items.build())
            } else {
                val service = Intent(context, TimeBricksWidgetService::class.java)
                    .setData(Uri.parse("timebricks-widget://list/$id/$day/$page/$themeMode/${Uri.encode(events.firstOrNull()?.id.orEmpty())}"))
                    .putExtra(DAY, day).putExtra(PAGE, page).putExtra("widget_anchor", events.firstOrNull()?.id)
                views.setRemoteAdapter(R.id.widget_list, service)
            }
            if (resetScroll || focus) views.setScrollPosition(R.id.widget_list, 0)
            views.setViewVisibility(R.id.widget_pages, if (events.size > WidgetPolicy.PAGE_SIZE) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_page_label, "${page + 1}/${(events.size + WidgetPolicy.PAGE_SIZE - 1) / WidgetPolicy.PAGE_SIZE}页 · ${events.size}项")
            views.setOnClickPendingIntent(R.id.widget_page_previous, command(context, id, "page", page = (page - 1).coerceAtLeast(0)))
            views.setOnClickPendingIntent(R.id.widget_page_next, command(context, id, "page", page = WidgetPolicy.page(page + 1, events.size)))
            return views
        }
        val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
            SizeF(160f, 120f) to layout(false, true), SizeF(240f, 120f) to layout(false), SizeF(280f, 220f) to layout(true)
        )) else {
            val options = manager.getAppWidgetOptions(id)
            val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val width = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 160)
            val height = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
            layout(width >= 280 && height >= 220, width < 240)
        }
        manager.updateAppWidget(id, views)
        if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    fun row(context: Context, day: Long, event: WidgetEvent, themeMode: String): RemoteViews = RemoteViews(context.packageName, WidgetAppearance.rowLayout(themeMode)).apply {
        WidgetAppearance.row(this, themeMode)
        setTextViewText(R.id.widget_event_title, event.title.take(160))
        setTextViewText(R.id.widget_event_time, "${WidgetPolicy.clock(event.start)}–${WidgetPolicy.clock(event.end)}")
        setViewVisibility(R.id.widget_event_end, View.GONE)
        setTextViewText(R.id.widget_event_duration, WidgetPolicy.duration(event.end - event.start))
        setInt(R.id.widget_event_color, "setBackgroundColor", event.color)
        setContentDescription(R.id.widget_event_card, "${event.title}，${WidgetPolicy.clock(event.start)}至${WidgetPolicy.clock(event.end)}，${WidgetPolicy.duration(event.end - event.start)}")
        setOnClickFillInIntent(R.id.widget_event_card, Intent().putExtra(DAY, day).putExtra(EVENT, event.id))
    }

    fun legacyRows(context: Context, intent: Intent): WidgetLegacyRows {
        val day = intent.getLongExtra(DAY, LocalDate.now().toEpochDay())
        if (!WidgetPolicy.validDay(day)) return WidgetLegacyRows(day, emptyList(), "system")
        val snapshot = readWidgetSnapshot(context.applicationContext as Application, setOf(day))
        val events = snapshot.days[day].orEmpty()
        val anchor = intent.getStringExtra("widget_anchor")
        val ordered = WidgetPolicy.fromIndex(events, events.indexOfFirst { it.id == anchor }.coerceAtLeast(0))
        val page = WidgetPolicy.page(intent.getIntExtra(PAGE, 0), ordered.size)
        return WidgetLegacyRows(day, ordered.drop(page * WidgetPolicy.PAGE_SIZE).take(WidgetPolicy.PAGE_SIZE), snapshot.themeMode)
    }

    private fun scheduleUpdate(context: Context, enabled: Boolean, at: Long = 0) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, TimeBricksWidgetProvider::class.java).setAction(MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!enabled) manager.cancel(pending)
        else manager.set(AlarmManager.RTC, at, pending)
    }
}

class TimeBricksWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ScheduleWidgets.accepts(intent.action)) return
        val pending = goAsync()
        ScheduleWidgets.dispatch(context, intent) { pending.finish() }
    }
}

// Android 8–11 fallback; Android 12+ uses the direct RemoteCollectionItems API.
class TimeBricksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var day = 0L
        private var themeMode = "system"
        private var events = emptyList<WidgetEvent>()
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() {
            val token = android.os.Binder.clearCallingIdentity()
            try {
                val snapshot = ScheduleWidgets.legacyRows(applicationContext, intent)
                day = snapshot.day; events = snapshot.events; themeMode = snapshot.themeMode
            } catch (error: Exception) {
                events = emptyList()
                android.util.Log.e("TimeBricksWidget", "Cannot load collection", error)
            } finally { android.os.Binder.restoreCallingIdentity(token) }
        }
        override fun onDestroy() { events = emptyList() }
        override fun getCount() = events.size
        override fun getViewAt(position: Int): RemoteViews? = events.getOrNull(position)?.let { ScheduleWidgets.row(applicationContext, day, it, themeMode) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
