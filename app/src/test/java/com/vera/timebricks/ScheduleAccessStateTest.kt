package com.vera.timebricks

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleAccessStateTest {
    @Test fun historicalRecordWithoutSystemGrantsIsExpired() {
        assertEquals(ScheduleReadStatus.EXPIRED, scheduleReadStatus(false, false, false, true))
        assertEquals(ScheduleReadStatus.NOT_GRANTED, scheduleReadStatus(false, false, false, false))
    }

    @Test fun eachApiVersionMustBeChecked() {
        for (recorded in listOf(false, true)) {
            assertEquals(ScheduleReadStatus.GRANTED, scheduleReadStatus(false, true, true, recorded))
            assertEquals(ScheduleReadStatus.PARTIAL, scheduleReadStatus(false, true, false, recorded))
            assertEquals(ScheduleReadStatus.PARTIAL, scheduleReadStatus(false, false, true, recorded))
        }
    }

    @Test fun signatureAccessSurvivesManualRevocation() {
        for (v1 in listOf(false, true)) for (v2 in listOf(false, true)) for (recorded in listOf(false, true)) {
            assertEquals(ScheduleReadStatus.SIGNATURE, scheduleReadStatus(true, v1, v2, recorded))
        }
    }
}
