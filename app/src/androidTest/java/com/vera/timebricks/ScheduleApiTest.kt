package com.vera.timebricks

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduleApiTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val file get() = File(app.filesDir, "timebricks-data-v1.json")
    private val date = LocalDate.parse("2026-11-04")
    private val day = date.toEpochDay()
    private val zone = ZoneId.of("Asia/Singapore")
    private fun uri(path: String) = Uri.parse("content://${app.packageName}.schedule/v1/$path")
    private fun event(id: String, start: Int = 540, end: Int = 600) = JSONObject()
        .put("id", id).put("start", start).put("end", end).put("categoryId", "work").put("childId", "focus")
    private fun fixture(): JSONObject = JSONObject("""{
      "version":6,"categories":[{"id":"work","name":"工作","color":-123456,"archived":false,
      "children":[{"id":"focus","name":"专注","color":-654321,"archived":false}]}],
      "days":[],"templates":[],"defaultHistory":[],"repeatRules":[],"repeatExceptions":[],"categoryPauses":[],
      "settings":{"mainRows":2,"childRows":1,"darkTheme":true,"themeMode":"dark","themeHex":"527F93",
      "fontScale":1,"spacingScale":1,"cornerDp":16,"recentColors":[]}}
    """)
    private fun JSONObject.dated(events: JSONArray = JSONArray().put(event("dated")), on: Long = day): JSONObject =
        apply { getJSONArray("days").put(JSONObject().put("day", on).put("events", events)) }
    private fun JSONObject.template(revision: Int = 1, start: Int = 600): JSONObject = apply {
        val items = JSONArray().put(event("item", start, start + 60))
        getJSONArray("templates").put(JSONObject().put("id", "tpl").put("name", "模板").put("revision", revision).put("archived", false).put("events", items))
        getJSONArray("defaultHistory").put(JSONObject().put("templateId", "tpl").put("name", "模板").put("revision", revision).put("fromDay", day - 10).put("events", items))
    }
    private fun JSONObject.repeatRule(weekdays: List<Int> = (1..7).toList(), status: String = "active"): JSONObject = apply {
        getJSONArray("repeatRules").put(JSONObject().put("id", "rule").put("versions", JSONArray().put(
            JSONObject().put("fromDay", day - 10).put("revision", 1).put("name", "重复")
                .put("removed", false).put("events", JSONArray().put(event("rule-item-rule", 720, 780)))
                .put("startDay", day - 10).put("endDay", JSONObject.NULL).put("weekdays", JSONArray(weekdays)).put("status", status))))
    }
    private fun nativeRepeat(on: Long = day) = "repeat-" + UUID.nameUUIDFromBytes(JSONArray().put("rule").put(on).toString().toByteArray())
    private fun JSONObject.exception(cancel: Boolean = false): JSONObject = apply {
        getJSONArray("repeatExceptions").put(JSONObject().put("ruleId", "rule").put("day", day)
            .put("replacement", if (cancel) JSONObject.NULL else JSONArray().put(event(nativeRepeat(), 900, 960))))
    }
    private fun rows(root: JSONObject, on: LocalDate = date) = scheduleRowsFromBackup(root.toString().toByteArray(), on, zone)
    private fun write(root: JSONObject) { file.writeText(root.toString()) }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected failure") } catch (_: Exception) { } }
    private fun privateCall(name: String, vararg args: Any?): Any? {
        val method = Class.forName("com.vera.timebricks.MainActivityKt").declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(null, *args)
    }
    @Before fun setup() {
        check(app.packageName == "com.vera.timebricks.scheduleapitest") { "Use -PscheduleApiCheck=true; never touch user data" }
        file.delete(); File(file.path + ".bak").delete()
    }
    @After fun cleanup() {
        if (app.packageName == "com.vera.timebricks.scheduleapitest") { file.delete(); File(file.path + ".bak").delete() }
    }
    @Test fun completeCursorAndMeta() {
        write(fixture().dated().template().repeatRule())
        app.contentResolver.query(uri("events?date=$date"), null, null, null, null)!!.use {
            assertEquals(2, it.count)
            assertTrue(it.extras.getBoolean("complete")); assertEquals("ok", it.extras.getString("status"))
            assertEquals(2, it.extras.getInt("row_count")); assertEquals(ScheduleContract.EVENT_COLUMNS.toList(), it.columnNames.toList())
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(it.getColumnIndexOrThrow("notes")))
        }
        app.contentResolver.query(uri("meta"), null, null, null, null)!!.use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals(1, it.getInt(it.getColumnIndexOrThrow("api_version")))
            assertEquals(6, it.getInt(it.getColumnIndexOrThrow("data_json_version")))
        }
    }
    @Test fun emptyIsSuccessful() {
        write(fixture())
        app.contentResolver.query(uri("events?date=$date"), null, null, null, null)!!.use {
            assertEquals(0, it.count); assertEquals("ok", it.extras.getString("status")); assertTrue(it.extras.getBoolean("complete"))
        }
    }
    @Test fun absentAndCorruptAreFailures() {
        fails { app.contentResolver.query(uri("events?date=$date"), null, null, null, null) }
        file.writeText("{bad")
        fails { app.contentResolver.query(uri("events?date=$date"), null, null, null, null) }
        fails { app.contentResolver.query(uri("meta"), null, null, null, null) }
        assertEquals("{bad", file.readText())
    }
    @Test fun strictStoredJsonRejectsDuplicateKeysAndTrailingJunk() {
        file.writeText(fixture().toString() + "oops"); fails { readScheduleApiSnapshot(app, date) }
        file.writeText(fixture().toString().replace("\"version\":6", "\"version\":6,\"version\":6"))
        fails { readScheduleApiSnapshot(app, date) }
    }
    @Test fun atomicBackupReadDoesNotWrite() {
        file.writeText("broken")
        val backup = File(file.path + ".bak"); backup.writeText(fixture().dated().toString())
        assertEquals(1, readScheduleApiSnapshot(app, date).rows.size)
        assertEquals("broken", file.readText()); assertTrue(backup.exists())
    }
    @Test fun invalidQueriesFail() {
        write(fixture())
        listOf("events", "events?date=2026-02-30", "events?date=2026-1-1", "events?date=0000-01-01",
            "events?date=$date&date=$date", "events?date=$date&extra=1", "meta?date=$date", "other").forEach {
            fails { app.contentResolver.query(uri(it), null, null, null, null) }
        }
        fails { app.contentResolver.query(Uri.parse("content://${app.packageName}.schedule/v2/meta"), null, null, null, null) }
        fails { app.contentResolver.query(uri("meta"), arrayOf("missing"), null, null, null) }
        fails { app.contentResolver.query(uri("meta"), null, "1=1", null, null) }
        fails { app.contentResolver.query(uri("meta"), null, Bundle().apply { putInt("limit", 0) }, null) }
    }
    @Test fun projectionAndCancellation() {
        write(fixture().dated())
        app.contentResolver.query(uri("events?date=$date"), arrayOf("title", "event_instance_id"), null, null, null)!!.use {
            assertEquals(2, it.columnCount); it.moveToFirst(); assertEquals("工作·专注", it.getString(0))
        }
        fails { app.contentResolver.query(uri("meta"), null, null, null, null, CancellationSignal().apply { cancel() }) }
    }
    @Test fun mutationsAndFilesFail() {
        write(fixture().dated()); val before = file.readBytes()
        fails { app.contentResolver.insert(uri("events"), ContentValues()) }
        fails { app.contentResolver.update(uri("events"), ContentValues(), null, null) }
        fails { app.contentResolver.delete(uri("events"), null, null) }
        fails { app.contentResolver.bulkInsert(uri("events"), emptyArray()) }
        fails { app.contentResolver.applyBatch("${app.packageName}.schedule", arrayListOf()) }
        fails { app.contentResolver.call(uri("meta"), "wipe", null, null) }
        fails { app.contentResolver.openFileDescriptor(uri("meta"), "r") }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun templateAndIndependentBlank() {
        assertEquals(1, rows(fixture().template()).size)
        assertEquals(0, rows(fixture().template().dated(JSONArray())).size)
        assertEquals(1, rows(fixture().template().dated(JSONArray()).repeatRule()).size)
    }
    @Test fun dailyInstanceAndTimeEditIdentity() {
        val root = fixture().repeatRule(); val first = rows(root).single()
        assertNotEquals(first.instanceId, rows(root, date.plusDays(1)).single().instanceId)
        val edited = rows(root.exception()).single()
        assertEquals(first.instanceId, edited.instanceId); assertEquals("rule", edited.ruleId)
        assertEquals(900, edited.startMinute); assertTrue(edited.isOverride)
    }
    @Test fun singleCancelAndStatus() {
        assertTrue(rows(fixture().repeatRule().exception(true)).isEmpty())
        listOf("paused", "archived", "deleted").forEach { assertTrue(rows(fixture().repeatRule(status = it).exception()).isEmpty()) }
    }
    @Test fun weeklyBoundaryAndDetachedException() {
        assertTrue(rows(fixture().repeatRule(listOf(1))).isEmpty())
        assertEquals(1, rows(fixture().repeatRule(listOf(1)).exception()).size)
        val root = fixture().repeatRule()
        root.getJSONArray("repeatRules").getJSONObject(0).getJSONArray("versions").getJSONObject(0).put("endDay", day)
        assertEquals(1, rows(root).size); assertTrue(rows(root, date.plusDays(1)).isEmpty())
    }
    @Test fun templateRevisionTimeEditKeepsIdentity() {
        val original = rows(fixture().template()).single()
        val changed = rows(fixture().template(2, 660)).single()
        assertEquals(original.instanceId, changed.instanceId); assertNotEquals(original.nativeEventId, changed.nativeEventId)
        assertNotEquals(original.instanceId, rows(fixture().template(), date.plusDays(1)).single().instanceId)
    }
    @Test fun oldFrozenTemplateMigrationSurvivesHistoryRemoval() {
        val root = fixture().template().put("version", 5)
        val native = rows(root).single().nativeEventId; val expected = rows(root).single().instanceId
        root.dated(JSONArray().put(event(native, 660, 720)))
        val migrated = JSONObject(privateCall("toJson", privateCall("dataFromJson", root.toString())) as String)
        assertEquals(8, migrated.getInt("version")); assertEquals(expected, rows(migrated).single().instanceId)
        migrated.put("defaultHistory", JSONArray()).put("templates", JSONArray())
        assertEquals(expected, rows(migrated).single().instanceId)
    }
    @Test fun zipAndApiHaveIdenticalIdentity() {
        val root = fixture().template().repeatRule().exception(); write(root)
        val jsonRows = rows(root)
        val zipped = AutoBackupZip.pack(root.toString().toByteArray())
        assertEquals(jsonRows, scheduleRowsFromBackup(zipped, date, zone))
        assertEquals(jsonRows.map { it.instanceId }, readScheduleApiSnapshot(app, date).rows.map { it.instanceId })
    }
    @Test fun actualUiResolverAndApiHaveSameNativeRows() {
        val fixtures = listOf(fixture(), fixture().template(), fixture().template().repeatRule(), fixture().repeatRule().exception(),
            fixture().repeatRule().exception(true), fixture().dated(JSONArray()).template().repeatRule())
        fixtures.forEach { root ->
            val model = privateCall("dataFromJson", root.toString())!!
            val events = privateCall("eventsOn", model, day) as List<*>
            val ids = events.map { event -> event!!.javaClass.getDeclaredMethod("getId").apply { isAccessible = true }.invoke(event) }.toSet()
            assertEquals(ids, rows(root).map { it.nativeEventId }.toSet())
        }
    }
    @Test fun categoryArchiveIntervalRespectsHistory() {
        val root = fixture().repeatRule()
        root.getJSONArray("categories").getJSONObject(0).put("archived", true)
        root.getJSONArray("categoryPauses").put(JSONObject().put("parentId", "work").put("childId", JSONObject.NULL)
            .put("fromDay", day).put("untilDay", JSONObject.NULL))
        assertTrue(rows(root).isEmpty()); assertEquals(1, rows(root, date.minusDays(1)).size)
    }
    @Test fun legacyRepeatedNativeIdIsNamespacedByDate() {
        val root = fixture().dated().dated(on = day + 1)
        assertNotEquals(rows(root).single().instanceId, rows(root, date.plusDays(1)).single().instanceId)
    }
    @Test fun fullDayMidnightAndTimezone() {
        val row = rows(fixture().dated(JSONArray().put(event("all-day", 0, 1440)))).single()
        assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(), row.startMs)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), row.endMs)
        val elsewhere = scheduleRowsFromBackup(fixture().dated().toString().toByteArray(), date, ZoneId.of("UTC")).single()
        assertEquals(rows(fixture().dated()).single().instanceId, elsewhere.instanceId)
    }
    @Test fun queriesDoNotChangeBytesAndTokenChangesOnSave() {
        write(fixture().dated()); val before = file.readBytes(); val first = readScheduleApiSnapshot(app, date)
        assertEquals(first.token, readScheduleApiSnapshot(app, date).token); assertArrayEquals(before, file.readBytes())
        write(fixture().dated(JSONArray().put(event("dated", 600, 660))))
        val changed = readScheduleApiSnapshot(app, date)
        assertNotEquals(first.token, changed.token); assertEquals(first.rows.single().instanceId, changed.rows.single().instanceId)
    }
    @Test fun realUiTimeEditAndBackupRoundTripPreserveIdentity() {
        listOf(fixture().dated(), fixture().template(), fixture().repeatRule()).forEach { root ->
            val original = rows(root).single()
            val model = privateCall("dataFromJson", root.toString())!!
            val transform: (Any) -> Any = { e ->
                fun get(name: String) = e.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(e)
                e.javaClass.declaredConstructors.single { it.parameterCount == 9 }.apply { isAccessible = true }
                    .newInstance(1000, 1060, get("getCategoryId"), get("getChild"), get("getId"), get("getScheduleIdentity"), get("getDayOffset"), get("getImportedTemplateId"), get("getOriginDay"))
            }
            val changed = privateCall("modifyEvent", model, day, original.nativeEventId, transform)!!
            val bytes = privateCall("backupBytes", changed) as ByteArray
            val restored = scheduleRowsFromBackup(AutoBackupZip.pack(bytes), date, zone).single()
            assertEquals(original.instanceId, restored.instanceId); assertEquals(1000, restored.startMinute)
        }
    }
    @Test fun allLegacyVersionsRemainImportable() {
        val modelClass = Class.forName("com.vera.timebricks.AppData")
        val model = modelClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val initial = privateCall("toJson", model) as String
        (1..5).forEach { version ->
            val root = JSONObject(initial).put("version", version)
            if (version < 5) root.remove("categoryPauses")
            if (version < 4) { root.remove("repeatRules"); root.remove("repeatExceptions") }
            if (version < 3) { root.remove("templates"); root.remove("defaultHistory") }
            val migrated = privateCall("dataFromJson", root.toString())!!
            val bytes = privateCall("backupBytes", migrated) as ByteArray
            assertEquals(if (version < 3) 7 else 0, scheduleRowsFromBackup(bytes, date, zone).size)
        }
    }
    @Test fun uiWidgetAndStatusOverlayKeepTheirExistingProjection() {
        val root = fixture().template().repeatRule().exception()
        val model = privateCall("dataFromJson", root.toString())!!
        val widgets = privateCall("widgetEvents", model, day) as List<*>
        val ids = widgets.map { it!!.javaClass.getDeclaredMethod("getId").apply { isAccessible = true }.invoke(it) }.toSet()
        assertEquals(rows(root).map { it.nativeEventId }.toSet(), ids)
        // StatusScheduleOverlay.currentStatusSchedule uses readWidgetSnapshot, same projection.
    }
    @Test fun independentIdentityReferenceVectors() {
        // Expected values calculated separately with Python hashlib and big-endian byte lengths.
        assertEquals("tb1-b656338343730e88f2b0012edc5be42d635024a7db8fb3f72f3a6c46b1c5a11b",
            ScheduleContract.instanceId(20761, "event:dated"))
        assertEquals("tb1-27aab9c82fb530ff843a030cf8ce3b143dbf0e3ad210b5ed0f6a0662a074eff8",
            ScheduleContract.instanceId(20761, "rule:rule"))
        assertEquals("template-73a7bedd56308eb1a7a1bf5b59d7ae9d8b9817fad31d3d765a73fec9d0eb918d",
            ScheduleContract.templateIdentity("tpl", "item"))
        assertEquals("tb1-b7cc3187cf44036b53594dae6072be254ffb569434c31cae171abe195b4d3f71",
            ScheduleContract.instanceId(20761, ScheduleContract.templateIdentity("tpl", "item")))
    }
    @Test fun dstAndCorruptBackupFailAsWholeSnapshot() {
        val spring = LocalDate.parse("2026-03-08")
        val root = fixture().dated(JSONArray().put(event("gap", 150, 180)), spring.toEpochDay())
        fails { scheduleRowsFromBackup(root.toString().toByteArray(), spring, ZoneId.of("America/New_York")) }
        fails { scheduleRowsFromBackup("{bad".toByteArray(), date, zone) }
        fails { scheduleRowsFromBackup((fixture().toString() + "junk").toByteArray(), date, zone) }
        val badZip = AutoBackupZip.pack(fixture().dated().toString().toByteArray()).copyOf(30)
        fails { scheduleRowsFromBackup(badZip, date, zone) }
        val duplicate = fixture().dated(JSONArray().put(event("same")).put(event("same")))
        fails { rows(duplicate) }
    }
}
