package com.vera.timebricks

import java.math.BigInteger

/** Pure policy, shared by release discovery and the final APK checks. */
internal object AppUpdatePolicy {
    private val versionPattern = Regex("^[vV]?(\\d+(?:\\.\\d+){0,3})(?:\\+[0-9A-Za-z.-]+)?$")
    fun version(value: String): List<BigInteger>? = versionPattern.matchEntire(value.trim())
        ?.groupValues?.get(1)?.split('.')?.map { it.toBigInteger() }

    fun compareVersions(left: String, right: String): Int? {
        val a = version(left) ?: return null
        val b = version(right) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val compared = (a.getOrNull(i) ?: BigInteger.ZERO).compareTo(b.getOrNull(i) ?: BigInteger.ZERO)
            if (compared != 0) return compared
        }
        return 0
    }

    fun sha256(digest: String): String? = digest.takeIf {
        it.matches(Regex("sha256:[0-9a-fA-F]{64}"))
    }?.substringAfter(':')?.lowercase()

    fun shouldAutoCheck(now: Long, lastAttempt: Long): Boolean =
        lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= 24 * 60 * 60 * 1000L

    fun compatibilityError(
        installedPackage: String, candidatePackage: String,
        installedCode: Long, candidateCode: Long,
        installedSigners: Set<String>, candidateSigners: Set<String>,
        deviceSdk: Int, minimumSdk: Int
    ): String? = when {
        installedPackage != candidatePackage -> "安装包包名不一致，不能覆盖当前应用。"
        candidateCode <= installedCode -> "安装包版本号未递增，不能覆盖当前版本。"
        minimumSdk > deviceSdk -> "此版本需要 Android API $minimumSdk 或更高版本。"
        installedSigners.isEmpty() || candidateSigners != installedSigners ->
            "安装包签名与当前应用不一致，已停止安装。请使用相同签名的版本，不要卸载现有应用。"
        else -> null
    }
}
