package com.vera.timebricks

import android.database.DatabaseUtils
import android.database.SQLException
import android.database.sqlite.SQLiteException
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Framework Parcel regression; no provider files or app data are opened. */
class ProviderErrorTransportTest {
    @Test fun capabilityErrorSurvivesFrameworkExceptionParcel() {
        val parcel = Parcel.obtain()
        try {
            DatabaseUtils.writeExceptionToParcel(parcel, SQLiteException("TB_REQUIRES_API_V2"))
            parcel.setDataPosition(0)
            try { DatabaseUtils.readExceptionFromParcel(parcel); fail("Expected capability error") }
            catch (e: SQLException) { assertEquals("TB_REQUIRES_API_V2", e.message) }
        } finally { parcel.recycle() }
    }
}
