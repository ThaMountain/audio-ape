package com.audioape.plugin.protocol

/**
 * Immutable, host-readable retry decision for one failed provider action.
 *
 * A `code` of [PluginErrorCode.FORBIDDEN] *always* yields [RetryDecision.STOP] — the
 * automated host never retries an authentication failure (401) in a tight loop
 * (architecture §4.3). Slow-backoff calls also stop entirely; staggered-429 falls back
 * to [recommendedDelayMs].
 */
enum class RetryDecision {
    /** Surface the failure path now; do not automatically retry at all. */
    STOP,

    /** Retry once after [RetryPolicy.recommendedDelayMs]; used for 429 with a bounded cap. */
    RETRY_AFTER,
}

/**
 * Bounded 429 policy: the provider's `Retry-After` value is sanitized into
 * [recommendedDelayMs] (nonnegative, capped by [MAX_DELAY_MS]) and the attempt budget
 * is capped by [MAX_ATTEMPTS], so a hostile header cannot create an unbounded retry
 * loop.
 */
data class RetryPolicy(
    val code: PluginErrorCode,
    val decision: RetryDecision,
    val recommendedDelayMs: Long = 0L,
    val maxAttempts: Int = 1,
    val attempt: Int = 1,
) {
    init {
        require(recommendedDelayMs >= 0L) { "retry delay must be nonnegative" }
        require(recommendedDelayMs <= MAX_DELAY_MS) { "retry delay exceeds the host cap" }
        require(maxAttempts >= 1) { "retry max attempts must be positive" }
        require(attempt >= 1) { "retry attempt must be positive" }
        require(
            decision != RetryDecision.RETRY_AFTER || code == PluginErrorCode.LIMIT_EXCEEDED,
        ) {
            "only rate-limit rejections may be scheduled for retry"
        }
        require(
            decision != RetryDecision.STOP || code != PluginErrorCode.FORBIDDEN || recommendedDelayMs == 0L,
        ) {
            "auth failures must never be scheduled for retry"
        }
        require(decision == RetryDecision.STOP || recommendedDelayMs > 0L) {
            "a retry-able decision needs a bounded positive delay"
        }
        require(
            decision == RetryDecision.STOP || attempt < maxAttempts,
        ) {
            "an exhausted retry budget must stop, not retry"
        }
    }

    val canRetry: Boolean
        get() = decision == RetryDecision.RETRY_AFTER

    companion object {
        /** Absolute cap applied to provider-suggested delays; 0 disables a stale hint. */
        const val MAX_DELAY_MS = 300_000L

        /** Fixed cap on provider-rate-limit retry attempts (bounded retry policy). */
        const val MAX_ATTEMPTS = 3

        /** 401 must never be retried by automation. */
        fun stopFor(
            code: PluginErrorCode,
            detail: String? = null,
        ): RetryPolicy = RetryPolicy(code = code, decision = RetryDecision.STOP, attempt = 1, maxAttempts = 1)

        /**
         * Bounded 429 policy. A null or negative `retryAfterMs` (no/useless header)
         * keeps the default backoff. When [maxAttempts] is reached the decision becomes
         * [RetryDecision.STOP].
         */
        fun forRateLimit(
            attempt: Int,
            retryAfterMs: Long? = null,
            maxAttempts: Int = MAX_ATTEMPTS,
        ): RetryPolicy {
            val delay =
                if (retryAfterMs == null || retryAfterMs <= 0L) {
                    DEFAULT_RATE_LIMIT_DELAY_MS
                } else {
                    minOf(retryAfterMs, MAX_DELAY_MS)
                }
            val stillRetryable = attempt < maxAttempts
            return RetryPolicy(
                code = PluginErrorCode.LIMIT_EXCEEDED,
                decision = if (stillRetryable) RetryDecision.RETRY_AFTER else RetryDecision.STOP,
                recommendedDelayMs = delay,
                maxAttempts = maxAttempts,
                attempt = attempt,
            )
        }

        /** Default backoff for a provider rate limit with no usable Retry-After header. */
        const val DEFAULT_RATE_LIMIT_DELAY_MS = 5_000L
    }
}
