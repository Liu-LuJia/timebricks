package com.vera.timebricks

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Real Android URI grants to the test APK, strictly within the isolated test application. */
class ScheduleAccessSystemTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val recipient get() = instrumentation.context.packageName

    @Before fun isolate() {
        check(app.packageName == "com.vera.timebricks.scheduleapitest") { "Use -PscheduleApiCheck=true" }
        ScheduleAccess.revoke(app, recipient)
    }

    @After fun cleanup() {
        if (app.packageName == "com.vera.timebricks.scheduleapitest") ScheduleAccess.revoke(app, recipient)
    }

    @Test fun grantBothVersionsThenRevokeAndRemoveRecord() {
        ScheduleAccess.grant(app, recipient)
        val entry = readScheduleAccessEntries(app).single { it.packageName == recipient }
        assertEquals(ScheduleReadStatus.GRANTED, entry.status)
        ScheduleAccess.revoke(app, recipient)
        assertFalse(ScheduleAccess.packages(app).contains(recipient))
        for (version in 1..2) {
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                app.checkUriPermission(ScheduleAccess.root(app, version), -1,
                    app.packageManager.getApplicationInfo(recipient, 0).uid, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    @Test fun partialAndExpiredSystemGrantsDoNotLookFullyAuthorized() {
        ScheduleAccess.grant(app, recipient)
        app.revokeUriPermission(recipient, ScheduleAccess.root(app, 2), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(ScheduleReadStatus.PARTIAL, readScheduleAccessEntries(app).single { it.packageName == recipient }.status)
        // Reboot-equivalent state: persisted history remains but Android no longer has either grant.
        app.revokeUriPermission(recipient, ScheduleAccess.root(app, 1), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(ScheduleReadStatus.EXPIRED, readScheduleAccessEntries(app).single { it.packageName == recipient }.status)
    }
}
