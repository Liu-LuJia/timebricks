package com.vera.timebricks

import java.util.Locale

internal fun scheduleClock(minute: Int): String {
    val local = Math.floorMod(minute, 1440)
    return String.format(Locale.ROOT, "%d:%02d", local / 60, local % 60)
}

/** Collapse only identical overnight occurrences of the same series, never unrelated events.
 * Keep the active occurrence in the morning and the upcoming occurrence after it ends.
 * This is a read-only projection; each retained row still opens its original event ID. */
internal fun <T> compactOvernightRows(
    events: List<T>, minute: Int, start: (T) -> Int, end: (T) -> Int, series: (T) -> String?
): List<T> {
    val groups = events.filter { series(it) != null &&
        Math.floorDiv(start(it), 1440) != Math.floorDiv(end(it) - 1, 1440) }
        .groupBy { Triple(series(it), Math.floorMod(start(it), 1440), end(it) - start(it)) }
    val hidden = groups.values.flatMap { rows ->
        // Do not collapse overlaps or duplicate rows on the same date.
        if (rows.size < 2 || rows.map { Math.floorDiv(start(it), 1440) }.distinct().size != rows.size ||
            rows.any { end(it) - start(it) >= 1440 }) emptyList()
        else {
            val keep = rows.firstOrNull { start(it) <= minute && minute < end(it) }
                ?: rows.filter { start(it) >= 0 }.minByOrNull(start) ?: rows.maxBy(start)
            rows.filter { it != keep }
        }
    }.toSet()
    return events.filterNot { it in hidden }
}
