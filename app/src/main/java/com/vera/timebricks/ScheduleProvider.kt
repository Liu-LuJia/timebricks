package com.vera.timebricks

import android.app.Application
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import java.time.LocalDate

/** No separate process/cache: shares the writer lock and reads a complete committed snapshot. */
class ScheduleProvider : ContentProvider() {
    override fun onCreate() = true

    private fun route(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority == "${requireNotNull(context).packageName}.schedule" &&
            uri.fragment == null && uri.path in setOf("/v1/meta", "/v1/events", "/v2/meta", "/v2/events")) { "TB_UNSUPPORTED_URI" }
        return uri.lastPathSegment!!
    }

    override fun getType(uri: Uri): String = when (route(uri)) {
        "meta" -> "vnd.android.cursor.item/vnd.com.vera.timebricks.schedule.meta.${uri.pathSegments.first()}"
        else -> "vnd.android.cursor.dir/vnd.com.vera.timebricks.schedule.events.${uri.pathSegments.first()}"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        cancellationSignal?.throwIfCanceled()
        val route = route(uri)
        val apiVersion = if (uri.pathSegments.first() == "v2") 2 else 1
        require(selection == null && selectionArgs == null && sortOrder == null) { "TB_UNSUPPORTED_QUERY" }
        val date: LocalDate? = if (route == "events") {
            require(uri.queryParameterNames == setOf("date") && uri.getQueryParameters("date").size == 1) { "TB_INVALID_DATE" }
            try { ScheduleContract.date(uri.getQueryParameter("date")) }
            catch (_: java.time.DateTimeException) { throw IllegalArgumentException("TB_INVALID_DATE") }
        } else {
            require(uri.queryParameterNames.isEmpty()) { "TB_UNSUPPORTED_QUERY" }
            null
        }
        val all = if (date == null) ScheduleContract.META_COLUMNS else if (apiVersion == 2) ScheduleContract.EVENT_COLUMNS_V2 else ScheduleContract.EVENT_COLUMNS
        val columns = projection?.toList() ?: all.toList()
        require(columns.isNotEmpty() && columns.distinct().size == columns.size && columns.all { it in all }) { "TB_INVALID_PROJECTION" }
        val snapshot = try {
            readScheduleApiSnapshot(requireNotNull(context).applicationContext as Application, date, apiVersion)
        } catch (error: Exception) {
            // Only fixed status codes cross the process boundary; never log schedule contents.
            // DatabaseUtils explicitly parcels SQLiteException (a SQLException).
            // A plain SQLException is not a supported cross-process exception type.
            throw SQLiteException(when (error.message) {
                "TB_NOT_INITIALIZED" -> "TB_NOT_INITIALIZED"
                "TB_TIME_ZONE_CONFLICT" -> "TB_TIME_ZONE_CONFLICT"
                "TB_REQUIRES_API_V2" -> "TB_REQUIRES_API_V2"
                else -> "TB_READ_FAILED"
            })
        }
        cancellationSignal?.throwIfCanceled()
        val indexes = columns.map { all.indexOf(it) }
        val cursor = MatrixCursor(columns.toTypedArray(), if (date == null) 1 else snapshot.rows.size)
        try {
            fun add(values: Array<Any?>) { cursor.addRow(indexes.map { values[it] }) }
            if (date == null) add(arrayOf(apiVersion, ScheduleContract.IDENTITY_VERSION,
                ScheduleContract.SOURCE_ID, snapshot.jsonVersion, "${requireNotNull(context).packageName}.permission.READ_SCHEDULE",
                "signature_or_user_uri_grant", snapshot.zone, snapshot.token, "ok", 1))
            else snapshot.rows.forEachIndexed { index, row ->
                cancellationSignal?.throwIfCanceled()
                add(if (apiVersion == 2) row.valuesV2(index) else row.values(index))
            }
            cursor.extras = Bundle().apply {
                putString("status", "ok")
                putBoolean("complete", true)
                putInt("api_version", apiVersion)
                putInt("identity_version", ScheduleContract.IDENTITY_VERSION)
                putString("source_id", ScheduleContract.SOURCE_ID)
                putString("date", date?.toString())
                putString("time_zone", snapshot.zone)
                putString("snapshot_token", snapshot.token)
                putInt("row_count", cursor.count)
            }
            return cursor
        } catch (error: Throwable) { cursor.close(); throw error }
    }

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        require(queryArgs == null || queryArgs.isEmpty) { "TB_UNSUPPORTED_QUERY" }
        return query(uri, projection, null, null, null, cancellationSignal)
    }

    private fun readOnly(): Nothing = throw UnsupportedOperationException("TB_READ_ONLY")
    override fun insert(uri: Uri, values: ContentValues?): Uri = readOnly()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = readOnly()
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = readOnly()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = readOnly()
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor = readOnly()
    override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor = readOnly()
}
