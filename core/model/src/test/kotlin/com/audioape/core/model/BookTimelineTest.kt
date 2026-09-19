package com.audioape.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTimelineTest {
    @Test
    fun `prefix sums and binary search resolve boundaries`() {
        val timeline = BookTimeline.fromDurations(listOf(1_000L, 2_000L, 3_000L))

        assertEquals(listOf(0L, 1_000L, 3_000L), timeline.partStartsMilliseconds)
        assertEquals(6_000L, timeline.totalDurationMilliseconds)
        assertEquals(PartPosition(0, 999L, 999L, false), timeline.toPartPosition(999L))
        assertEquals(PartPosition(1, 1_000L, 0L, false), timeline.toPartPosition(1_000L))
        assertEquals(PartPosition(2, 3_000L, 0L, false), timeline.toPartPosition(3_000L))
    }

    @Test
    fun `positions clamp and exact end stays on final part`() {
        val timeline = BookTimeline.fromDurations(listOf(1_000L, 2_000L))

        assertEquals(PartPosition(0, 0L, 0L, false), timeline.toPartPosition(-1L))
        assertEquals(PartPosition(1, 3_000L, 2_000L, true), timeline.toPartPosition(3_000L))
        assertEquals(PartPosition(1, 3_000L, 2_000L, true), timeline.toPartPosition(Long.MAX_VALUE))
    }

    @Test
    fun `durations longer than 24 hours use checked 64 bit arithmetic`() {
        val thirtyHours = 30L * 60L * 60L * 1_000L
        val timeline = BookTimeline.fromDurations(listOf(thirtyHours, thirtyHours))

        assertEquals(60L * 60L * 60L * 1_000L, timeline.totalDurationMilliseconds)
        assertEquals(
            PartPosition(1, thirtyHours + 123L, 123L, false),
            timeline.toPartPosition(thirtyHours + 123L),
        )
        assertEquals(thirtyHours + 123L, timeline.toBookPosition(1, 123L))
        assertThrows(ArithmeticException::class.java) {
            BookTimeline.fromDurations(listOf(Long.MAX_VALUE, 1L))
        }
    }

    @Test
    fun `unknown duration makes book wide mapping explicitly unavailable`() {
        val timeline = BookTimeline.fromDurations(listOf(1_000L, null, 2_000L))

        assertFalse(timeline.isComplete)
        assertEquals(listOf(0L, 1_000L, null), timeline.partStartsMilliseconds)
        assertNull(timeline.totalDurationMilliseconds)
        assertNull(timeline.toPartPosition(500L))
        assertEquals(500L, timeline.toBookPosition(0, 500L))
        assertNull(timeline.toBookPosition(1, 0L))
        assertNull(timeline.toBookPosition(2, 0L))
    }

    @Test
    fun `local conversion validates bounds`() {
        val timeline = BookTimeline.fromDurations(listOf(1_000L, 2_000L))

        assertTrue(timeline.isComplete)
        assertEquals(3_000L, timeline.toBookPosition(1, 2_000L))
        assertThrows(IllegalArgumentException::class.java) {
            timeline.toBookPosition(1, 2_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeline.toBookPosition(2, 0L)
        }
    }
}
