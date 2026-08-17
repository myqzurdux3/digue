package com.insta.reelsoff.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

private val TODAY: LocalDate = LocalDate.of(2026, 8, 17)

class DailyWatchedTest {

    @Test
    fun `an empty log still yields one entry per day`() {
        val days = dailyWatched(emptyList(), PARIS, TODAY, 3)

        assertEquals(3, days.size)
        assertEquals(listOf(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 16), TODAY), days.map { it.date })
        assertEquals(listOf(0L, 0L, 0L), days.map { it.millis })
    }

    @Test
    fun `passes on the same day are summed`() {
        val events = listOf(
            PassEvent(epochMillis = at(17, 20, 5), durationMillis = 120_000),
            PassEvent(epochMillis = at(17, 20, 40), durationMillis = 60_000),
        )

        assertEquals(180_000L, dailyWatched(events, PARIS, TODAY, 3).last().millis)
    }

    @Test
    fun `passes are bucketed by their local day`() {
        val events = listOf(
            PassEvent(epochMillis = at(15, 20, 0), durationMillis = 60_000),
            PassEvent(epochMillis = at(17, 20, 0), durationMillis = 30_000),
        )

        assertEquals(listOf(60_000L, 0L, 30_000L), dailyWatched(events, PARIS, TODAY, 3).map { it.millis })
    }

    @Test
    fun `a pass just before local midnight belongs to the day that is ending`() {
        val events = listOf(PassEvent(epochMillis = at(16, 23, 59), durationMillis = 45_000))

        assertEquals(listOf(0L, 45_000L, 0L), dailyWatched(events, PARIS, TODAY, 3).map { it.millis })
    }

    @Test
    fun `passes outside the window are ignored`() {
        val events = listOf(
            PassEvent(epochMillis = at(10, 20, 0), durationMillis = 60_000),
            PassEvent(epochMillis = at(18, 20, 0), durationMillis = 60_000),
        )

        assertEquals(listOf(0L, 0L, 0L), dailyWatched(events, PARIS, TODAY, 3).map { it.millis })
    }

    @Test
    fun `a nonpositive duration is dropped rather than subtracted`() {
        // closePass floors its elapsed time at zero, so this cannot happen from the
        // current code — but the database outlives any one build, and a stray
        // negative row must not make the day read as less than it was.
        val events = listOf(
            PassEvent(epochMillis = at(17, 20, 0), durationMillis = 60_000),
            PassEvent(epochMillis = at(17, 20, 30), durationMillis = -60_000),
            PassEvent(epochMillis = at(17, 21, 0), durationMillis = 0),
        )

        assertEquals(60_000L, dailyWatched(events, PARIS, TODAY, 3).last().millis)
    }

    @Test
    fun `the indices line up with dailyCounts, so the two can be read side by side`() {
        val blocks = listOf(BlockEvent(epochMillis = at(16, 20, 0), surface = "REELS", ruleTier = "HIGH"))
        val passes = listOf(PassEvent(epochMillis = at(16, 20, 30), durationMillis = 90_000))

        val counted = dailyCounts(blocks, PARIS, TODAY, 3)
        val watched = dailyWatched(passes, PARIS, TODAY, 3)

        assertEquals(counted.map { it.date }, watched.map { it.date })
        assertEquals(1, counted[1].total)
        assertEquals(90_000L, watched[1].millis)
    }
}
