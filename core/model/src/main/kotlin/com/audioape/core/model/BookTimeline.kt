package com.audioape.core.model

/** A resolved book-wide position and its part-local equivalent. */
data class PartPosition(
    val partIndex: Int,
    val bookPositionMilliseconds: Long,
    val localOffsetMilliseconds: Long,
    val atBookEnd: Boolean,
)

/**
 * Deterministic conversion between a book-wide timeline and ordered media parts.
 *
 * Starts are checked 64-bit prefix sums. A timeline with any unknown duration exposes known prefix
 * starts but deliberately refuses book-wide resolution, since neither later starts nor a total can
 * be established without guessing.
 */
@ConsistentCopyVisibility
data class BookTimeline private constructor(
    val durationsMilliseconds: List<Long?>,
    val partStartsMilliseconds: List<Long?>,
    val totalDurationMilliseconds: Long?,
) {
    val isComplete: Boolean
        get() = totalDurationMilliseconds != null

    /**
     * Clamps [bookPositionMilliseconds] to the complete book range, then finds the rightmost part
     * start not after it with binary search. At the exact book end, the result is the end of the
     * final part. Returns null while any part duration is unknown.
     */
    fun toPartPosition(bookPositionMilliseconds: Long): PartPosition? {
        val total = totalDurationMilliseconds ?: return null
        val clamped = bookPositionMilliseconds.coerceIn(0L, total)

        var low = 0
        var high = partStartsMilliseconds.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val start = requireNotNull(partStartsMilliseconds[middle])
            if (start <= clamped) {
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        val partIndex = high.coerceAtLeast(0)
        val partStart = requireNotNull(partStartsMilliseconds[partIndex])
        return PartPosition(
            partIndex = partIndex,
            bookPositionMilliseconds = clamped,
            localOffsetMilliseconds = clamped - partStart,
            atBookEnd = clamped == total,
        )
    }

    /**
     * Converts a validated local offset to book time. Returns null when the selected duration or
     * its prefix start is unknown. Checked addition protects the 64-bit invariant.
     */
    fun toBookPosition(
        partIndex: Int,
        localOffsetMilliseconds: Long,
    ): Long? {
        require(partIndex in durationsMilliseconds.indices) { "part index is out of range" }
        require(localOffsetMilliseconds >= 0L) { "local offset must be nonnegative" }

        val duration = durationsMilliseconds[partIndex] ?: return null
        require(localOffsetMilliseconds <= duration) { "local offset exceeds part duration" }
        val start = partStartsMilliseconds[partIndex] ?: return null
        return Math.addExact(start, localOffsetMilliseconds)
    }

    companion object {
        /** Builds a timeline in supplied playback order. Durations must be positive when known. */
        fun fromDurations(durationsMilliseconds: List<Long?>): BookTimeline {
            require(durationsMilliseconds.isNotEmpty()) { "a book timeline needs at least one part" }
            require(durationsMilliseconds.all { it == null || it > 0L }) {
                "known media part durations must be greater than zero"
            }

            val durations = durationsMilliseconds.toList()
            val starts = MutableList<Long?>(durations.size) { null }
            var runningTotal: Long? = 0L
            durations.forEachIndexed { index, duration ->
                starts[index] = runningTotal
                runningTotal =
                    if (runningTotal == null || duration == null) {
                        null
                    } else {
                        Math.addExact(runningTotal, duration)
                    }
            }

            return BookTimeline(
                durationsMilliseconds = durations,
                partStartsMilliseconds = starts.toList(),
                totalDurationMilliseconds = runningTotal,
            )
        }

        /** Builds a timeline from parts after enforcing `(bookId, orderIndex)` uniqueness. */
        fun fromParts(parts: List<MediaPart>): BookTimeline {
            require(parts.isNotEmpty()) { "a book timeline needs at least one media part" }
            validateUniqueMediaPartOrder(parts)
            val bookId = parts.first().bookId
            require(parts.all { it.bookId == bookId }) { "all timeline parts must belong to one book" }
            val ordered = parts.sortedBy(MediaPart::orderIndex)
            return fromDurations(ordered.map(MediaPart::cachedDurationMilliseconds))
        }
    }
}
