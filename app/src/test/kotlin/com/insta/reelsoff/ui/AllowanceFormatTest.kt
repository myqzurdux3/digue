package com.insta.reelsoff.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceFormatTest {

    @Test
    fun `a duration under a minute reads in seconds`() {
        assertEquals("40 s", formatDuration(40_000))
        assertEquals("0 s", formatDuration(0))
    }

    @Test
    fun `a duration under an hour reads in minutes and seconds`() {
        assertEquals("5 min 00 s", formatDuration(300_000))
        assertEquals("3 min 20 s", formatDuration(200_000))
    }

    @Test
    fun `a duration of an hour or more reads in hours and minutes`() {
        assertEquals("1 h 00", formatDuration(3_600_000))
        assertEquals("21 h 14", formatDuration(21 * 3_600_000L + 14 * 60_000))
    }

    @Test
    fun `a partial second rounds down, so a countdown never overstates`() {
        assertEquals("39 s", formatDuration(39_999))
    }

    @Test
    fun `a minute of day reads as a local wall-clock time`() {
        assertEquals("20 h 00", formatMinuteOfDay(20 * 60))
        assertEquals("00 h 00", formatMinuteOfDay(0))
        assertEquals("09 h 05", formatMinuteOfDay(9 * 60 + 5))
    }
}

class AllowanceEditorsTest {

    @Test
    fun `the quota choices are ordered and start at one minute`() {
        assertEquals(listOf(60_000L, 300_000L, 600_000L, 900_000L, 1_800_000L), QUOTA_CHOICES)
    }

    @Test
    fun `the cooldown choices start at none, which is where a fresh install sits`() {
        assertEquals(
            listOf(0L, 3_600_000L, 6 * 3_600_000L, 24 * 3_600_000L, 72 * 3_600_000L),
            COOLDOWN_CHOICES,
        )
    }

    @Test
    fun `every choice formats to something a reader recognises`() {
        assertEquals("1 min 00 s", formatDuration(QUOTA_CHOICES.first()))
        assertEquals("30 min 00 s", formatDuration(QUOTA_CHOICES.last()))
        assertEquals("1 h 00", formatDuration(COOLDOWN_CHOICES[1]))
        assertEquals("72 h 00", formatDuration(COOLDOWN_CHOICES.last()))
    }

    @Test
    fun `stepping a minute forward and back moves by the step`() {
        assertEquals(20 * 60 + 15, stepMinute(20 * 60, 15))
        assertEquals(19 * 60 + 45, stepMinute(20 * 60, -15))
    }

    @Test
    fun `stepping wraps around midnight in both directions`() {
        assertEquals(0, stepMinute(23 * 60 + 45, 15))
        assertEquals(23 * 60 + 45, stepMinute(0, -15))
    }

    @Test
    fun `stepping always lands inside a day`() {
        var minute = 0
        repeat(200) {
            minute = stepMinute(minute, 15)
            assertTrue(minute in 0 until 1440)
        }
        repeat(200) {
            minute = stepMinute(minute, -15)
            assertTrue(minute in 0 until 1440)
        }
    }
}
