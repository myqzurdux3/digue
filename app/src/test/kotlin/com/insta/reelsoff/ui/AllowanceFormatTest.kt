package com.insta.reelsoff.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
        assertEquals("20 h 00", formatMinuteOfDay(20 * 60, Locale.FRENCH))
        assertEquals("00 h 00", formatMinuteOfDay(0, Locale.FRENCH))
        assertEquals("09 h 05", formatMinuteOfDay(9 * 60 + 5, Locale.FRENCH))
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
    fun `every choice gets a label short enough to sit in a row of five`() {
        // Measured on the device: "30 min 00 s" does not fit and Compose wraps it
        // to one glyph per line — 16 px wide, 293 tall. A preset drops what is
        // always zero; only the countdown needs the seconds.
        assertEquals(listOf("1 min", "5 min", "10 min", "15 min", "30 min"), QUOTA_CHOICES.map(::formatChoice))
        assertEquals(listOf("1 h", "6 h", "24 h", "72 h"), COOLDOWN_CHOICES.drop(1).map(::formatChoice))
    }

    @Test
    fun `a choice that is not a whole hour keeps its minutes`() {
        assertEquals("1 h 30", formatChoice(90 * 60_000L))
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

    @Test
    fun `a file size reads at the coarsest useful unit`() {
        assertEquals("0 o", formatBytes(0, Locale.FRENCH))
        assertEquals("999 o", formatBytes(999, Locale.FRENCH))
        assertEquals("1,0 ko", formatBytes(1_000, Locale.FRENCH))
        assertEquals("76,1 ko", formatBytes(76_098, Locale.FRENCH))
        assertEquals("1,0 Mo", formatBytes(1_000_000, Locale.FRENCH))
        assertEquals("2,4 Mo", formatBytes(2_412_345, Locale.FRENCH))
    }

    @Test
    fun `a wall-clock time follows the language, French spelling out the hour`() {
        assertEquals("20 h 00", formatMinuteOfDay(20 * 60, Locale.FRENCH))
        assertEquals("20:00", formatMinuteOfDay(20 * 60, Locale.ENGLISH))
        assertEquals("09:05", formatMinuteOfDay(9 * 60 + 5, Locale.US))
    }

    @Test
    fun `a French regional variant is still French`() {
        // Compared on the language, not the locale: fr-CA and fr-BE would fall
        // through to English on an equality check.
        assertEquals("20 h 00", formatMinuteOfDay(20 * 60, Locale.CANADA_FRENCH))
    }

    @Test
    fun `an unlisted language falls back to English, as the resource folders do`() {
        assertEquals("20:00", formatMinuteOfDay(20 * 60, Locale("es")))
        // The unit name falls back; the decimal mark does not. Spanish writes
        // "1,0" whatever the unit is called, and that is the right answer — the
        // number belongs to the reader's locale, only the word is borrowed.
        assertEquals("1,0 kB", formatBytes(1_000, Locale("es")))
    }

    @Test
    fun `a file size carries both the unit name and the decimal mark of its language`() {
        assertEquals("0 B", formatBytes(0, Locale.ENGLISH))
        assertEquals("999 B", formatBytes(999, Locale.ENGLISH))
        assertEquals("1.0 kB", formatBytes(1_000, Locale.ENGLISH))
        assertEquals("76.1 kB", formatBytes(76_098, Locale.ENGLISH))
        assertEquals("2.4 MB", formatBytes(2_412_345, Locale.ENGLISH))
    }

    @Test
    fun `a duration reads the same in both languages, so it takes no locale`() {
        // h, min and s are the same symbols either side of the Channel. This is
        // asserted rather than assumed: the day it stops being true, the test
        // fails and formatDuration has to grow a locale like the others.
        assertEquals("21 h 14", formatDuration(21 * 3_600_000L + 14 * 60_000))
        assertEquals("3 min 20 s", formatDuration(200_000))
        assertEquals("40 s", formatDuration(40_000))
    }

    @Test
    fun `a negative size never shows a minus sign`() {
        // Cannot come from File.length(), but the figure sits next to a delete
        // button and "-1 o à supprimer" would read as a bug in the user's data.
        assertEquals("0 o", formatBytes(-1, Locale.FRENCH))
    }
}
