package com.vera.timebricks

import android.app.Application
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors

/** Settings are intentionally separate from calendar backups: an imported calendar never enables an overlay. */
internal data class StatusScheduleOptions(
    val bottomDp: Int = 0,
    val horizontalDp: Int = 0,
    val widthPercent: Int = 90,
    val fontSp: Int = 14,
    val opacityPercent: Int = 65,
    val textColor: Int = 0xFFD0D0D0.toInt()
)

internal object StatusScheduleOverlay {
    private const val PREFS = "status-schedule-v1"
    private const val VERTICAL_EDGE_MARGIN_DP = 32
    // Alarm broadcasts keep changing the displayed schedule during Doze. A Handler alone
    // is not a wake-up mechanism on a locked phone.
    private const val ALARM_EXACT = "com.vera.timebricks.statusschedule.BOUNDARY_EXACT"
    private const val ALARM_FALLBACK = "com.vera.timebricks.statusschedule.BOUNDARY_FALLBACK"
    private const val ALARM_BUFFER_MS = 300L
    private val alarmExecutor = Executors.newSingleThreadExecutor()
    private val alarmLock = Any()
    internal const val ACTION_REFRESH = "com.vera.timebricks.statusschedule.REFRESH"
    internal const val ACTION_STOP = "com.vera.timebricks.statusschedule.STOP"
    internal const val ACTION_OPTIONS = "com.vera.timebricks.statusschedule.OPTIONS"
    internal const val ACTION_PREVIEW = "com.vera.timebricks.statusschedule.PREVIEW"
    internal const val EXTRA_PREVIEW_ENABLED = "previewEnabled"

    /** Entire physical screen, not just a fixed 250 dp. Recomputed for rotation and display size. */
    fun maxBottomDp(context: Context): Int {
        val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val heightPx = if (Build.VERSION.SDK_INT >= 30) window.maximumWindowMetrics.bounds.height()
            else context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density
        return (heightPx / density - VERTICAL_EDGE_MARGIN_DP).toInt().coerceAtLeast(0)
    }

    /** While editing, display a non-interactive capsule to show the exact overlay bounds. */
    fun setEditing(context: Context, editing: Boolean) {
        if (!isEnabled(context)) return
        // Start/reach the service explicitly on entering the page; a first-launch broadcast
        // could otherwise arrive before the service registers its receiver.
        if (editing) runCatching {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, StatusScheduleOverlayService::class.java).apply {
                    action = ACTION_PREVIEW
                    putExtra(EXTRA_PREVIEW_ENABLED, true)
                })
        }.onFailure { Log.w("TimeBricksStatus", "Cannot begin overlay preview", it) }
        context.applicationContext.sendBroadcast(Intent(ACTION_PREVIEW).setPackage(context.packageName)
            .putExtra(EXTRA_PREVIEW_ENABLED, editing))
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
    fun options(context: Context): StatusScheduleOptions = prefs(context).let { p ->
        StatusScheduleOptions(
            bottomDp = p.getInt("bottomDp", 0).coerceIn(0, maxBottomDp(context)),
            horizontalDp = p.getInt("horizontalDp", 0).coerceIn(-180, 180),
            widthPercent = p.getInt("widthPercent", 90).coerceIn(20, 100),
            fontSp = p.getInt("fontSp", 14).coerceIn(10, 24),
            opacityPercent = p.getInt("opacityPercent", 65).coerceIn(20, 100),
            textColor = p.getInt("textColor", 0xFFD0D0D0.toInt())
        )
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !hasPermission(context)) return false
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (enabled) {
            ensureRunning(context)
            rescheduleAlarmsAsync(context)
        } else {
            cancelAlarms(context)
            context.stopService(Intent(context, StatusScheduleOverlayService::class.java))
        }
        return true
    }

    fun saveOptions(context: Context, options: StatusScheduleOptions) {
        prefs(context).edit()
            .putInt("bottomDp", options.bottomDp.coerceIn(0, maxBottomDp(context)))
            .putInt("horizontalDp", options.horizontalDp.coerceIn(-180, 180))
            .putInt("widthPercent", options.widthPercent.coerceIn(20, 100))
            .putInt("fontSp", options.fontSp.coerceIn(10, 24))
            .putInt("opacityPercent", options.opacityPercent.coerceIn(20, 100))
            .putInt("textColor", options.textColor).apply()
        if (isEnabled(context)) context.applicationContext.sendBroadcast(
            Intent(ACTION_OPTIONS).setPackage(context.packageName))
    }

    /** Called from the visible app or system boot; background start restrictions may deny it. */
    fun ensureRunning(context: Context) {
        if (!isEnabled(context) || !hasPermission(context)) return
        runCatching {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, StatusScheduleOverlayService::class.java))
        }.onFailure { Log.w("TimeBricksStatus", "Cannot launch overlay service", it) }
    }

    /** Calendar edits must reschedule boundaries even if the overlay service was killed. */
    fun requestRefresh(context: Context) {
        if (!isEnabled(context)) return
        notifyRunningService(context)
        rescheduleAlarmsAsync(context)
    }

    private fun notifyRunningService(context: Context) {
        context.applicationContext.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName))
    }

    private fun alarmIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, if (action == ALARM_EXACT) 2305 else 2306,
            Intent(context, StatusScheduleAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancelAlarms(context: Context) = synchronized(alarmLock) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(alarmIntent(context, ALARM_EXACT))
        alarms.cancel(alarmIntent(context, ALARM_FALLBACK))
    }

    /** Keep just the next boundary; every alarm delivery schedules the following one.
     *  An inexact backup survives revocation of exact-alarm access. */
    fun scheduleNext(context: Context, changeAtMs: Long) = synchronized(alarmLock) {
        if (!isEnabled(context) || !hasPermission(context)) {
            cancelAlarms(context)
            return@synchronized
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val exactIntent = alarmIntent(context, ALARM_EXACT)
        val fallbackIntent = alarmIntent(context, ALARM_FALLBACK)
        alarms.cancel(exactIntent)
        alarms.cancel(fallbackIntent)
        val at = (changeAtMs + ALARM_BUFFER_MS).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        // The inexact backup still runs when the user turns off "准时提醒".
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fallbackIntent)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, exactIntent)
            } catch (error: SecurityException) {
                Log.i("TimeBricksStatus", "Exact alarm permission unavailable; using fallback")
            }
        }
    }

    /** Uses the app's normal calendar projection, independent of notification reminder settings. */
    fun rescheduleAlarmsAsync(context: Context, finished: (() -> Unit)? = null) {
        val app = context.applicationContext as Application
        alarmExecutor.execute {
            try {
                if (!isEnabled(app) || !hasPermission(app)) {
                    cancelAlarms(app)
                } else {
                    val next = currentStatusSchedule(app).nextChangeAt
                    scheduleNext(app, next)
                }
            } catch (error: Exception) {
                Log.w("TimeBricksStatus", "Could not schedule next boundary", error)
                // A temporary file read error must not terminate the alarm chain.
                runCatching { scheduleNext(app, System.currentTimeMillis() + 10 * 60_000L) }
            } finally {
                finished?.invoke()
            }
        }
    }

    /** Invoked by a real system wake-up alarm (or after a restart/time change). */
    fun onSystemWake(context: Context, finished: () -> Unit) {
        if (!isEnabled(context) || !hasPermission(context)) {
            cancelAlarms(context)
            finished()
            return
        }
        // Deliver the refresh immediately while the alarm grants a short execution window.
        // The same call also restarts an OS-killed overlay service when permitted.
        ensureRunning(context)
        notifyRunningService(context)
        rescheduleAlarmsAsync(context, finished)
    }
}

private data class StatusScheduleText(val value: String?, val nextChangeAt: Long)
private data class DatedSchedule(val day: LocalDate, val event: WidgetEvent, val startMs: Long, val endMs: Long)

/** Reuses the same effective calendar projection as the widget, including repeats and exceptions. */
private fun currentStatusSchedule(application: Application): StatusScheduleText {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val tomorrow = today.plusDays(1)
    val days = readWidgetDays(application, setOf(today.toEpochDay(), tomorrow.toEpochDay()))
    val events = listOf(today, tomorrow).flatMap { day ->
        days[day.toEpochDay()].orEmpty().map { event ->
            val midnight = day.atStartOfDay()
            DatedSchedule(day, event,
                midnight.plusMinutes(event.start.toLong()).atZone(zone).toInstant().toEpochMilli(),
                midnight.plusMinutes(event.end.toLong()).atZone(zone).toInstant().toEpochMilli())
        }
    }
    val distinctEvents = events.distinctBy { Triple(it.event.id, it.startMs, it.endMs) }
    val active = distinctEvents.filter { it.startMs <= now && it.endMs > now }
        .maxWithOrNull(compareBy<DatedSchedule> { it.startMs }.thenBy { it.event.id })
    val next = distinctEvents.filter { it.startMs > now }.minWithOrNull(compareBy<DatedSchedule> { it.startMs }.thenBy { it.event.id })
    val selected = active ?: next
    val text = when {
        selected == null -> null
        active != null -> "${selected.event.title} ${scheduleClock(selected.event.start)}–${scheduleClock(selected.event.end)}"
        else -> "${if (selected.day == today) "下个" else "明天"} · ${selected.event.title} ${scheduleClock(selected.event.start)}–${scheduleClock(selected.event.end)}"
    }
    val nextMidnight = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
    val boundary = (events.flatMap { listOf(it.startMs, it.endMs) } + nextMidnight)
        .filter { it > now }.minOrNull() ?: (now + 60_000L)
    return StatusScheduleText(text, boundary)
}

class StatusScheduleOverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var label: TextView? = null
    private var attached = false
    private var currentText: String? = null
    private var editing = false
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val scheduledRefresh = Runnable { refresh() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                StatusScheduleOverlay.ACTION_PREVIEW -> {
                    editing = intent.getBooleanExtra(StatusScheduleOverlay.EXTRA_PREVIEW_ENABLED, false)
                    show(currentText)
                }
                StatusScheduleOverlay.ACTION_OPTIONS -> show(currentText)
                else -> refresh()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(StatusScheduleOverlay.ACTION_REFRESH)
            addAction(StatusScheduleOverlay.ACTION_OPTIONS)
            addAction(StatusScheduleOverlay.ACTION_PREVIEW)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            // Restore the visible text immediately when the screen wakes after deep sleep.
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == StatusScheduleOverlay.ACTION_STOP || !StatusScheduleOverlay.isEnabled(this) ||
            !StatusScheduleOverlay.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (intent?.action == StatusScheduleOverlay.ACTION_PREVIEW) {
                editing = intent.getBooleanExtra(StatusScheduleOverlay.EXTRA_PREVIEW_ENABLED, false)
            }
            promoteToForeground()
            refresh()
        } catch (error: Exception) {
            Log.w("TimeBricksStatus", "Overlay service cannot start", error)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "timebricks_status_schedule"
        manager.createNotificationChannel(NotificationChannel(channelId, "状态栏日程运行中",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持用户开启的底部日程悬浮文字持续更新"
            setShowBadge(false)
        })
        val opening = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_timebricks)
            .setContentTitle("TimeBricks")
            .setContentText("时间积木")
            .setContentIntent(opening)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2304, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2304, notification)
    }

    private fun refresh() {
        mainHandler.removeCallbacks(scheduledRefresh)
        if (!StatusScheduleOverlay.isEnabled(this) || !StatusScheduleOverlay.hasPermission(this)) {
            stopSelf(); return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { currentStatusSchedule(application) }
                    .onFailure { Log.w("TimeBricksStatus", "Failed to read schedule", it) }
                    .getOrNull()
            }
            currentText = result?.value
            show(currentText)
            val now = System.currentTimeMillis()
            val next = result?.nextChangeAt ?: (now + 60_000)
            val delay = (next - now + 100).coerceIn(1_000, 600_000)
            // Main-thread timer is a fast path while awake; AlarmManager is the Doze path.
            runCatching { StatusScheduleOverlay.scheduleNext(this@StatusScheduleOverlayService, next) }
                .onFailure { Log.w("TimeBricksStatus", "Could not set schedule alarm", it) }
            mainHandler.postDelayed(scheduledRefresh, delay)
        }
    }

    private fun show(content: String?) {
        // Provide a sample when today's schedule is empty so width/position remain editable.
        val displayText = content?.takeIf { it.isNotBlank() }
            ?: if (editing) "日程预览 · 12:00–13:00" else null
        if (displayText == null) { hide(); return }
        val options = StatusScheduleOverlay.options(this)
        val density = resources.displayMetrics.density
        val view = label ?: TextView(this).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            setHorizontallyScrolling(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            // An overlay window cannot take keyboard focus; selected state starts marquee.
            isSelected = true
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((8 * density).toInt())
            setPadding((12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt())
        }.also { label = it }
        // Do not reassign the same text on every slider tick: it restarts marquee animation.
        if (view.text.toString() != displayText) view.text = displayText
        val originalAlpha = android.graphics.Color.alpha(options.textColor)
        // Android 12+ blocks touch-through for sufficiently opaque untrusted overlay windows.
        // Cap the *window* opacity below 0.8 so underlying apps remain touchable.
        val windowAlpha = 0.79f
        val adjustedAlpha = (originalAlpha * options.opacityPercent / (100f * windowAlpha))
            .toInt().coerceIn(0, 255)
        val ink = (options.textColor and 0x00FFFFFF) or (adjustedAlpha shl 24)
        if (view.currentTextColor != ink) view.setTextColor(ink)
        if (kotlin.math.abs(view.textSize - options.fontSp * resources.displayMetrics.scaledDensity) > 0.5f) {
            view.textSize = options.fontSp.toFloat()
        }
        // Background changes only when entering/leaving edit mode, not on every slider tick.
        if (view.tag != editing) {
            if (editing) {
                view.background = GradientDrawable().apply {
                    setColor(0x99666666.toInt())
                    cornerRadius = 100 * density
                }
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            view.tag = editing
        }
        val displayWidth = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
            else resources.displayMetrics.widthPixels
        val layout = WindowManager.LayoutParams(
            (displayWidth * options.widthPercent / 100f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            alpha = windowAlpha
            x = (options.horizontalDp * density).toInt()
            y = (options.bottomDp.coerceAtMost(StatusScheduleOverlay.maxBottomDp(this@StatusScheduleOverlayService)) * density).toInt()
            title = "TimeBricks 状态栏日程"
        }
        try {
            if (attached) {
                val previous = view.layoutParams as? WindowManager.LayoutParams
                if (previous == null || previous.width != layout.width || previous.height != layout.height ||
                    previous.flags != layout.flags || previous.x != layout.x || previous.y != layout.y || previous.gravity != layout.gravity) {
                    wm.updateViewLayout(view, layout)
                }
            } else {
                wm.addView(view, layout)
                attached = true
            }
        } catch (error: Exception) {
            Log.w("TimeBricksStatus", "Cannot display overlay", error)
            hide()
        }
    }

    private fun hide() {
        if (attached) {
            runCatching { label?.let { wm.removeViewImmediate(it) } }
            attached = false
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scheduledRefresh)
        refreshJob?.cancel()
        scope.cancel()
        hide()
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}

/** Explicit alarm receiver continues to work when the app's main process was killed.
 *  System boot, package update and clock changes rebuild the next pending boundary. */
class StatusScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        try {
            StatusScheduleOverlay.onSystemWake(context) { result.finish() }
        } catch (error: Exception) {
            Log.w("TimeBricksStatus", "System wake handling failed", error)
            result.finish()
        }
    }
}
