package com.vera.timebricks

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    @Test fun numericVersionsDoNotCompareLexically() {
        assertTrue(AppUpdatePolicy.compareVersions("v1.10.0", "1.9.9")!! > 0)
        assertEquals(0, AppUpdatePolicy.compareVersions("V1.2", "1.2.0.0+build.8"))
        assertTrue(AppUpdatePolicy.compareVersions("1.1.2", "1.1.3")!! < 0)
        assertTrue(AppUpdatePolicy.compareVersions("999999999999999999999.0", "1.0")!! > 0)
    }

    @Test fun unrecognizedAndPreviewVersionsAreNotAssumedNewer() {
        listOf("", "latest", "1.2-rc1", "release-1.3", "1..3", "1.2.3.4.5").forEach {
            assertNull(AppUpdatePolicy.compareVersions(it, "1.1.3"))
        }
        assertNull(AppUpdatePolicy.compareVersions("1.2.0", "development"))
    }

    @Test fun requiresCompleteSha256FromReleaseMetadata() {
        assertEquals("a".repeat(64), AppUpdatePolicy.sha256("sha256:" + "A".repeat(64)))
        listOf("", "null", "sha256:123", "sha512:" + "a".repeat(64), "sha256:" + "g".repeat(64))
            .forEach { assertNull(AppUpdatePolicy.sha256(it)) }
    }

    @Test fun autoCheckIsThrottledAndRecoversFromClockRollback() {
        val now = 100_000_000L
        assertTrue(AppUpdatePolicy.shouldAutoCheck(now, 0))
        assertFalse(AppUpdatePolicy.shouldAutoCheck(now, now - 86_399_999))
        assertTrue(AppUpdatePolicy.shouldAutoCheck(now, now - 86_400_000))
        assertTrue(AppUpdatePolicy.shouldAutoCheck(now, now + 1))
    }

    private fun check(packageName: String = "com.vera.timebricks", code: Long = 6,
        signers: Set<String> = setOf("certificate"), sdk: Int = 26) = AppUpdatePolicy.compatibilityError(
        "com.vera.timebricks", packageName, 5, code, setOf("certificate"), signers, 26, sdk)

    @Test fun compatibleUpgradeIsAccepted() { assertNull(check()) }
    @Test fun rejectsOtherPackagesAndNonIncreasingCodes() {
        assertTrue(check(packageName = "com.vera.timebricks.debug")!!.contains("包名"))
        assertTrue(check(code = 5)!!.contains("版本号"))
        assertTrue(check(code = 4)!!.contains("版本号"))
    }
    @Test fun rejectsUnknownChangedAndAdditionalSigners() {
        assertTrue(check(signers = emptySet())!!.contains("签名"))
        assertTrue(check(signers = setOf("debug-certificate"))!!.contains("签名"))
        assertTrue(check(signers = setOf("certificate", "other"))!!.contains("签名"))
    }
    @Test fun rejectsUnsupportedMinimumSdk() { assertTrue(check(sdk = 27)!!.contains("Android API 27")) }
}
