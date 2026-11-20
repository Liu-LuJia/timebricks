package com.vera.timebricks

import org.junit.Assert.*
import org.junit.Test

class SchedulePresentationTest {
    private data class Row(val id: String, val start: Int, val end: Int, val series: String?)
    private fun compact(rows: List<Row>, minute: Int) =
        compactOvernightRows(rows, minute, { it.start }, { it.end }, { it.series })
    @Test fun fullRangeAcrossMidnightHasNoDatePrefix() {
        assertEquals("22:30", scheduleClock(1350))
        assertEquals("6:30", scheduleClock(1830))
        assertEquals("22:30", scheduleClock(-90))
        assertEquals("0:00", scheduleClock(1440))
    }
    @Test fun repeatedNightKeepsActiveThenUpcomingIdentity() {
        val old = Row("yesterday", -90, 390, "sleep")
        val next = Row("today", 1350, 1830, "sleep")
        assertEquals(listOf(old), compact(listOf(old, next), 0))
        assertEquals(listOf(old), compact(listOf(old, next), 389))
        assertEquals(listOf(next), compact(listOf(old, next), 390))
        assertEquals(listOf(next), compact(listOf(old, next), 1400))
    }
    @Test fun independentAndChangedOccurrencesRemainSeparate() {
        val rows = listOf(Row("a", -90, 390, null), Row("b", 1350, 1830, null))
        assertEquals(rows, compact(rows, 0))
        val changed = listOf(Row("a", -90, 390, "sleep"), Row("b", 1350, 1860, "sleep"))
        assertEquals(changed, compact(changed, 0))
        val overlap = listOf(Row("a", 1350, 1830, "sleep"), Row("b", 1350, 1830, "sleep"))
        assertEquals(overlap, compact(overlap, 0))
    }
}
