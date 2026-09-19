package com.audioape.plugin.protocol

import java.net.URI
import java.net.URISyntaxException
import java.text.Normalizer

/**
 * Shared constructor-time validation for the immutable boundary objects.
 *
 * These are assertions in the same style as `:core:model` (build-time asserts, no
 * exceptions expected across the boundary). They keep every protocol object internally
 * consistent so the host never has to re-validate what it was handed.
 */
fun requireNonBlank(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
}

fun requireOptionalNonBlank(
    value: String?,
    label: String,
) {
    value?.let { require(it.isNotBlank()) { "$label must not be blank" } }
}

fun requireNormalizedText(
    value: String,
    label: String,
) {
    requireNonBlank(value, label)
    require(value == value.trim() && Normalizer.normalize(value, Normalizer.Form.NFC) == value) {
        "$label must be trimmed and NFC-normalized"
    }
}

fun requireOptionalNormalizedText(
    value: String?,
    label: String,
) {
    value?.let { requireNormalizedText(it, label) }
}

fun requireOptionalUrl(
    value: String?,
    label: String,
) {
    value?.let { candidate ->
        requireNormalizedText(candidate, label)
        require(isHttpOrHttpsUrl(candidate)) {
            "$label must be an absolute https:// or http:// URL with no userinfo, query, or fragment"
        }
    }
}

fun isHttpOrHttpsUrl(candidate: String): Boolean =
    runCatching {
        val parsed = URI(candidate)
        val scheme = parsed.scheme?.lowercase()
        val userinfo = parsed.rawUserInfo
        val query = parsed.rawQuery
        val fragment = parsed.rawFragment
        val host = parsed.host
        (scheme == "https" || scheme == "http") &&
            userinfo == null &&
            query == null &&
            fragment == null &&
            !host.isNullOrEmpty()
    }.getOrDefault(false)
