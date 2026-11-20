package com.vera.timebricks

import java.time.LocalDate
import java.time.ZoneId
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Identity is shared by the provider and the backup projection. Never hash mutable fields. */
internal object ScheduleContract {
    const val VERSION = 1
    const val IDENTITY_VERSION = 1
    const val SOURCE_ID = "com.vera.timebricks"
    val EVENT_COLUMNS = arrayOf("_id", "source_id", "event_instance_id", "source_event_id",
        "native_event_id", "repeat_rule_id", "source_kind", "date", "start_time_ms", "end_time_ms",
        "time_zone", "start_minute", "end_minute", "title", "category_id", "category_name",
        "subcategory_id", "subcategory_name", "color_argb", "notes", "is_override")
    const val VERSION_V2 = 2
    val EVENT_COLUMNS_V2 = EVENT_COLUMNS + arrayOf("origin_date", "start_date", "end_date",
        "segment_start_minute", "segment_end_minute", "is_cross_day", "duration_minutes")
    val META_COLUMNS = arrayOf("api_version", "identity_version", "source_id", "data_json_version",
        "read_permission", "access_mode", "time_zone", "snapshot_token", "status", "read_only")

    fun date(value: String?): LocalDate {
        require(value != null && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "TB_INVALID_DATE" }
        return LocalDate.parse(value).also { require(it.year in 1..9998) { "TB_INVALID_DATE" } }
    }

    // Length-prefixed UTF-8 avoids differences between Android and desktop JSON escaping.
    private fun identityHash(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
    fun templateIdentity(templateId: String, itemId: String): String =
        "template-" + identityHash(templateId, itemId)
    fun instanceId(day: Long, identity: String): String = "tb1-" + identityHash(day.toString(), identity)

    fun timestamp(day: LocalDate, minute: Int, zone: ZoneId): Long =
        day.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
}

internal data class ScheduleApiRow(
    val instanceId: String, val sourceEventId: String, val nativeEventId: String,
    val ruleId: String?, val sourceKind: String, val date: String,
    val startMs: Long, val endMs: Long, val zone: String, val startMinute: Int, val endMinute: Int,
    val title: String, val categoryId: String, val categoryName: String,
    val childId: String?, val childName: String?, val color: Int, val isOverride: Boolean,
    val originDate: String = date, val startDate: String = date, val endDate: String = date,
    val segmentStart: Int = startMinute, val segmentEnd: Int = endMinute,
    val crossDay: Boolean = false, val durationMinutes: Int = endMinute - startMinute
) {
    fun valuesV2(index: Int): Array<Any?> = (values(index).toList() + listOf(originDate, startDate, endDate,
        segmentStart, segmentEnd, if (crossDay) 1 else 0, durationMinutes)).toTypedArray()
    fun values(index: Int): Array<Any?> = arrayOf(index.toLong(), ScheduleContract.SOURCE_ID,
        instanceId, sourceEventId, nativeEventId, ruleId, sourceKind, date, startMs, endMs, zone,
        startMinute, endMinute, title, categoryId, categoryName, childId, childName, color, null,
        if (isOverride) 1 else 0)
}

internal data class ScheduleApiSnapshot(
    val rows: List<ScheduleApiRow>, val token: String, val jsonVersion: Int, val zone: String
)
