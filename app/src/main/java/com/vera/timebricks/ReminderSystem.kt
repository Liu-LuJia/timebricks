package com.vera.timebricks

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

// This cache/settings file lives in noBackupFilesDir. Calendar JSON and exports are untouched.
internal data class ReminderOccurrence(
    val key: String, val day: Long, val start: Long, val end: Long, val title: String,
    val clock: String
)
internal data class ReminderPlan(val occurrence: ReminderOccurrence, val fireAt: Long)
internal data class ReminderStatus(
    val enabled: Boolean = false, val minutes: Int = 10, val notificationsAllowed: Boolean = false,
    val exactAllowed: Boolean = false, val nextAt: Long? = null, val error: String = ""
)

internal object ReminderPlanner {
    const val WINDOW_DAYS = 7L
    const val MAX_LATE_MS = 60 * 60 * 1000L
    // A reminder is delivered once per occurrence *start time*, not once per event ID.
    // Editing the start schedules a fresh notification; editing only the end does not.
    // Old v1 sent keys (without a start time) remain readable, but cannot suppress a
    // rescheduled occurrence whose original fire time was not recorded.
    fun deliveryKey(event: ReminderOccurrence): String = "${event.key}|start=${event.start}"
    fun future(events: List<ReminderOccurrence>, minutes: Int, now: Long, sent: Set<String>): List<ReminderPlan> =
        events.asSequence().filter { deliveryKey(it) !in sent }.map { ReminderPlan(it, it.start - minutes * 60_000L) }
            .filter { it.fireAt > now && it.fireAt <= now + WINDOW_DAYS * 86_400_000L }
            .distinctBy { it.occurrence.key }.sortedWith(compareBy({ it.fireAt }, { it.occurrence.key })).toList()

    // Only previously scheduled, unchanged instances may be delivered late. Never replay history.
    fun due(pending: List<ReminderPlan>, current: List<ReminderOccurrence>, minutes: Int,
            now: Long, sent: Set<String>): List<ReminderPlan> {
        val byKey = current.associateBy { it.key }
        return pending.filter { plan ->
            val event = byKey[plan.occurrence.key]
            event != null && deliveryKey(event) !in sent && event.start == plan.occurrence.start &&
                event.end == plan.occurrence.end && event.start - minutes * 60_000L == plan.fireAt &&
                plan.fireAt <= now && now - plan.fireAt <= MAX_LATE_MS && event.end > now
        }.distinctBy { it.occurrence.key }.map { it.copy(occurrence = byKey.getValue(it.occurrence.key)) }
    }
}

internal object ReminderSystem {
    // One process, one lock: durable calendar writes and notification revalidation cannot interleave.
    val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    const val CHANNEL = "timebricks_events_v1"
    private const val FIRE = "com.vera.timebricks.REMINDER_FIRE"
    private const val EXACT = "com.vera.timebricks.REMINDER_EXACT"
    private const val REFRESH = "com.vera.timebricks.REMINDER_REFRESH"
    private val mutableStatus = MutableStateFlow(ReminderStatus())
    val status = mutableStatus.asStateFlow()
    private data class State(
        val enabled: Boolean = false, val minutes: Int = 10,
        val pending: List<ReminderPlan> = emptyList(), val sent: Map<String, Long> = emptyMap()
    )

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "reminders-v1.json"))
    private fun read(context: Context): State {
        val file = file(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return State()
        val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(json.getInt("version") == 1)
        val minutes = json.getInt("minutes")
        require(minutes in listOf(0, 5, 10, 15, 30, 60))
        val pending = json.getJSONArray("pending")
        val sent = json.getJSONObject("sent")
        return State(json.getBoolean("enabled"), minutes, List(pending.length()) { i ->
            val item = pending.getJSONObject(i)
            ReminderPlan(ReminderOccurrence(item.getString("key"), item.getLong("day"), item.getLong("start"),
                item.getLong("end"), item.getString("title"), item.getString("clock")), item.getLong("fire"))
        }, sent.keys().asSequence().associateWith { sent.getLong(it) })
    }
    private fun write(context: Context, state: State) {
        val json = JSONObject().put("version", 1).put("enabled", state.enabled).put("minutes", state.minutes)
            .put("sent", JSONObject(state.sent)).put("pending", JSONArray().also { array ->
                state.pending.forEach { plan ->
                    val event = plan.occurrence
                    array.put(JSONObject().put("key", event.key).put("day", event.day).put("start", event.start)
                        .put("end", event.end).put("title", event.title).put("clock", event.clock).put("fire", plan.fireAt))
                }
            })
        val file = file(context)
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "时间积木的事件开始提醒"
            })
    }
    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun exact(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, TimeBricksReminderReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        cancelNext(context); alarms.cancel(intent(context, REFRESH))
    }
    private fun cancelNext(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(intent(context, FIRE)); alarms.cancel(intent(context, EXACT))
    }
    private fun maintenance(context: Context) {
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 6 * 60 * 60 * 1000L, 6 * 60 * 60 * 1000L, intent(context, REFRESH))
    }
    private fun schedule(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Keep an inexact fallback even when exact access is granted. Revoking exact access
        // kills the process and deletes exact alarms without a revocation broadcast.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, FIRE))
        if (exact(context)) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, EXACT)) }
            catch (_: SecurityException) { /* Permission can be revoked between check and call. */ }
        }
    }
    fun refreshAsync(context: Context, finish: (() -> Unit)? = null, clockChanged: Boolean = false) {
        val app = context.applicationContext
        executor.execute { try { refresh(app, clockChanged) } finally { finish?.invoke() } }
    }
    fun configure(context: Context, enabled: Boolean? = null, minutes: Int? = null) {
        val app = context.applicationContext
        executor.execute {
            synchronized(lock) {
                try {
                    require(minutes == null || minutes in listOf(0, 5, 10, 15, 30, 60))
                    val previous = read(app)
                    write(app, previous.copy(enabled = enabled ?: previous.enabled, minutes = minutes ?: previous.minutes,
                        pending = if (enabled == false || minutes != null) emptyList() else previous.pending))
                    refresh(app)
                } catch (error: Exception) { fail(app, error) }
            }
        }
    }
    // Called after each successful save (including restore/undo). Failures never roll back calendar data.
    fun refresh(context: Context, clockChanged: Boolean = false) = synchronized(lock) {
        try {
            channel(context)
            var state = read(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!state.enabled) {
                cancel(context)
                manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
                if (state.pending.isNotEmpty()) { state = state.copy(pending = emptyList()); write(context, state) }
                publish(context, state); return@synchronized
            }
            maintenance(context)
            if (!allowed(context)) {
                cancelNext(context)
                state = state.copy(pending = emptyList()); write(context, state)
                publish(context, state); return@synchronized
            }
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
            val current = readReminderOccurrences(context.applicationContext as Application, today - 1, today + 8, zone)
            // Existing cards are withdrawn when deleted, archived or changed, not only future alarms.
            val byKey = current.associateBy { it.key }
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { card ->
                val event = byKey[card.tag]
                if (event == null || event.end <= now || card.notification.extras.getLong("tb_start") != event.start ||
                    card.notification.extras.getLong("tb_end") != event.end || clockChanged) manager.cancel(card.tag, card.id)
            }
            // Keep delivery signatures 32 days; a changed start time may alert again.
            var sent = state.sent.filterValues { it >= today - 32 }
            val due = if (clockChanged) emptyList() else ReminderPlanner.due(state.pending, current, state.minutes, now, sent.keys)
            for (plan in due) {
                if (!allowed(context)) break
                val event = plan.occurrence
                // Durable claim precedes notify: repeated broadcasts/process restart cannot double-alert.
                val deliveryKey = ReminderPlanner.deliveryKey(event)
                sent = sent + (deliveryKey to event.day)
                state = state.copy(sent = sent)
                write(context, state)
                try {
                    val extras = android.os.Bundle().apply { putLong("tb_start", event.start); putLong("tb_end", event.end) }
                    val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_timebricks)
                        .setContentTitle(event.title).setContentText("${event.clock} 开始 · 时间积木")
                        .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).setOnlyAlertOnce(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(true).setWhen(event.start)
                        .setTimeoutAfter((event.end - now).coerceAtLeast(1)).addExtras(extras).build()
                    manager.notify(event.key, 1, notification)
                } catch (error: Exception) {
                    sent = sent - deliveryKey; state = state.copy(sent = sent); write(context, state)
                    throw error
                }
            }
            val future = ReminderPlanner.future(current, state.minutes, now, sent.keys)
            // Persist seven days, schedule only the next event (exact + inexact fallback).
            // At most three fixed system alarms including maintenance, regardless of rule count.
            state = state.copy(pending = future, sent = sent)
            write(context, state)
            cancelNext(context)
            future.firstOrNull()?.let { schedule(context, it.fireAt) }
            publish(context, state)
        } catch (error: Exception) { fail(context, error) }
    }
    private fun publish(context: Context, state: State) {
        mutableStatus.value = ReminderStatus(state.enabled, state.minutes, allowed(context), exact(context), state.pending.firstOrNull()?.fireAt)
    }
    private fun fail(context: Context, error: Exception) {
        // Fail closed: never send against a corrupt or unreadable schedule. Leave calendar untouched.
        runCatching { cancelNext(context) }
        android.util.Log.e("TimeBricksReminder", "Reminder refresh failed", error)
        mutableStatus.value = mutableStatus.value.copy(error = "提醒更新失败，请重试")
    }
}

// Must be declared in AndroidManifest.xml; exported=false still receives system broadcasts.
class TimeBricksReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val clockChanged = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ReminderSystem.refreshAsync(context, { pending.finish() }, clockChanged)
    }
}
