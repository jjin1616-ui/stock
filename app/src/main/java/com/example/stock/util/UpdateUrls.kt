package com.example.stock.util

import com.example.stock.data.api.LatestApkInfoDto

fun resolveLatestApkUrl(info: LatestApkInfoDto, defaultBaseUrl: String): String {
    // Always prefer the stable latest endpoint so users jump directly to newest build.
    val latestUrl = info.apkUrl.orEmpty().trim()
    if (latestUrl.isNotBlank()) return latestUrl

    // Backward-compatible fallback for legacy metadata.
    val versionedUrl = info.apkVersionedUrl.orEmpty().trim()
    if (versionedUrl.isNotBlank()) return versionedUrl

    return defaultBaseUrl.trimEnd('/') + "/apk/download"
}
