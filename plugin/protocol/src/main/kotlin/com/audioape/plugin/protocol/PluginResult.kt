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
    val rateLimit: RateLimitRejection? = null,
) {
    init {
        require(
            (value != null && rejection == null && rateLimit == null) ||
                (
                    value == null &&
                        rejection != null &&
                        (rateLimit == null || rateLimit.code == rejection.code)
                ),
        ) {
            "a plugin result must carry exactly one of value or rejection"
        }
    }

    val isSuccess: Boolean
        get() = value != null

    val isRejected: Boolean
        get() = rejection != null

    /** Bounded 429 hint on a rejected result, when the provider returned one. */
    val rateLimitHintMs: Long?
        get() = rateLimit?.retryAfterMs
}

/** Constructs a successful [PluginResult]. */
fun <T> pluginSuccess(value: T): PluginResult<T> = PluginResult(value, null)

/** Constructs a rejected [PluginResult]. */
fun <T> pluginRejected(
    code: PluginErrorCode,
    detail: String? = null,
): PluginResult<T> = PluginResult(null, PluginRejection(code, detail))

/**
 * Typed 401-shaped rejection: authentication for the provider action was required and
 * is absent or no longer valid. The host surfaces "auth-required" and never retries it
 * automatically (architecture §4.3 retry rule).
 */
fun <T> pluginAuthRequired(detail: String? = null): PluginResult<T> = pluginRejected(PluginErrorCode.FORBIDDEN, detail)

/**
 * Typed 429-shaped rejection: the provider rate-limited the action. [retryAfterMs]
 * carries a provider `Retry-After` hint when one was returned, sanitized to bounded
 * epoch-millis before it ever reaches the host.
 */
fun <T> pluginRateLimited(
    retryAfterMs: Long? = null,
    detail: String? = null,
): PluginResult<T> =
    PluginResult(
        null,
        PluginRejection(code = PluginErrorCode.LIMIT_EXCEEDED, detail = detail),
        RateLimitRejection(
            code = PluginErrorCode.LIMIT_EXCEEDED,
            retryAfterMs = retryAfterMs,
            detail = detail,
        ),
    )

/**
 * Immutable, typed 429 rejection carrying the provider's bounded `Retry-After` hint
 * as epoch-millis. The hint is sanitized ([PluginRejection] never carries raw URLs or
 * credentials; this never carries headers) and the host's retry policy re-caps it.
 */
data class RateLimitRejection(
    val code: PluginErrorCode,
    val retryAfterMs: Long? = null,
    val detail: String? = null,
) {
    init {
        require(code == PluginErrorCode.LIMIT_EXCEEDED) {
            "a rate limit rejection must use LIMIT_EXCEEDED"
        }
        require(retryAfterMs == null || (retryAfterMs >= 0L && retryAfterMs <= RetryPolicy.MAX_DELAY_MS)) {
            "rate limit retry-after must be bounded"
        }
        detail?.let { requireNonBlank(it, "rate limit rejection detail") }
    }
}

/**
 * A fixture-completed job that still has to produce its expiring link through the
 * provider worker (`resolver.debrid.files.v0` finishes in the host engine's provider
 * adapter, AA-021, never here). This is how v0 keeps the *fixture* static-data proof
 * honest: the fixture only carries the job and lets the host own the link.
 */
data class DeferredDownload(
    val releaseId: String,
    val status: ProviderJobStatus = ProviderJobStatus.READY,
    val detail: String? = null,
) {
    init {
        requireNonBlank(releaseId, "deferred download release id")
        detail?.let { requireNonBlank(it, "deferred download detail") }
    }
}
