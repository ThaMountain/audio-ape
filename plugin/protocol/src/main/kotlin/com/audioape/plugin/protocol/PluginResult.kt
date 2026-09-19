package com.audioape.plugin.protocol

/**
 * Closed set of typed errors a plugin primitive may report across the host boundary.
 *
 * The boundary never throws exceptions; a primitive returns [PluginResult] with either
 * a value or a [PluginRejection]. Unknown-missing inputs are typed `NOT_FOUND`, not
 * errors, so the host can distinguish "no data" (e.g., empty match list) from a real
 * failure.
 */
enum class PluginErrorCode {
    /** The requested resource was not found (typed empty, not an error). */
    NOT_FOUND,

    /** The requester called an operation its declared capabilities do not permit. */
    CAPABILITY_MISMATCH,

    /** The request referenced a resource the caller was not entitled to. */
    FORBIDDEN,

    /** A host budget or sanity limit was exceeded. */
    LIMIT_EXCEEDED,

    /** The underlying source failed; the plugin cannot fulfill the request. */
    SOURCE_UNAVAILABLE,

    /** The caller passed data the plugin cannot accept. */
    INVALID_ARGUMENT,
}

/**
 * Immutable, typed rejection of one plugin primitive call.
 *
 * [detail] is a short, human-readable, parameter-free string. It never carries
 * credentials, tokens, folder paths, or raw URLs.
 */
data class PluginRejection(
    val code: PluginErrorCode,
    val detail: String? = null,
) {
    init {
        detail?.let { requireNonBlank(it, "plugin rejection detail") }
    }
}

/**
 * Immutable result envelope for one plugin primitive call.
 *
 * Either [value] or [rejection] is set, never both, matching the "no exceptions
 * crossing the boundary" rule. Values are immutable boundary objects and never contain
 * Android, Room, SAF, or player types.
 */
data class PluginResult<T>(
    val value: T?,
    val rejection: PluginRejection? = null,
) {
    init {
        require((value != null && rejection == null) || (value == null && rejection != null)) {
            "a plugin result must carry exactly one of value or rejection"
        }
    }

    val isSuccess: Boolean
        get() = value != null

    val isRejected: Boolean
        get() = rejection != null
}

/** Constructs a successful [PluginResult]. */
fun <T> pluginSuccess(value: T): PluginResult<T> = PluginResult(value, null)

/** Constructs a rejected [PluginResult]. */
fun <T> pluginRejected(
    code: PluginErrorCode,
    detail: String? = null,
): PluginResult<T> = PluginResult(null, PluginRejection(code, detail))
