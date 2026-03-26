package com.example.stock.util

private val strictBuildLabel = Regex("""(?i)^V(?:\d+_)?(\d+)$""")
private val trailingDigits = Regex("""(\d+)$""")

fun versionCodeToBuildOrdinal(versionCode: Int?): Int? {
    val code = versionCode ?: return null
    if (code <= 0) return null
    return code % 100000
}

fun parseBuildOrdinal(label: String?): Int? {
    val raw = label?.trim().orEmpty()
    if (raw.isBlank()) return null
    strictBuildLabel.matchEntire(raw)?.let { m ->
        return m.groupValues[1].toIntOrNull()
    }
    trailingDigits.find(raw)?.let { m ->
        return m.groupValues[1].toIntOrNull()
    }
    return null
}

fun toShortBuildLabel(label: String?, fallbackVersionCode: Int? = null): String {
    val ord = parseBuildOrdinal(label) ?: versionCodeToBuildOrdinal(fallbackVersionCode)
    return if (ord != null) "V$ord" else label?.trim().orEmpty().ifBlank { "-" }
}

fun isRemoteBuildNewer(
    localVersionCode: Int,
    localBuildLabel: String?,
    remoteVersionCode: Int,
    remoteBuildLabel: String?,
): Boolean {
    val localOrd = parseBuildOrdinal(localBuildLabel) ?: versionCodeToBuildOrdinal(localVersionCode)
    val remoteOrd = parseBuildOrdinal(remoteBuildLabel) ?: versionCodeToBuildOrdinal(remoteVersionCode)
    if (localOrd != null && remoteOrd != null) {
        return remoteOrd > localOrd
    }
    return remoteVersionCode > localVersionCode
}
