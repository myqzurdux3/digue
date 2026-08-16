package com.insta.reelsoff.data

import com.insta.detection.Surface
import com.insta.detection.Tier
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyCountTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val today: LocalDate = LocalDate.of(2026, 8, 16)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(date.atTime(hour, minute), paris).toInstant().toEpochMilli()

    private fun event(millis: Long, surface: Surface) = BlockEvent(
        epochMillis = millis,
        surface = surface.name,
        ruleTier = Tier.HIGH.name,
    )

    @Test
    fun `returns one entry per day even when a day is empty`() {
        val counts = dailyCounts(emptyList(), paris, today, days = 14)

        assertEquals(14, counts.size)
        assertEquals(today.minusDays(13), counts.first().date)
        assertEquals(today, counts.last().date)
        assertEquals(0, counts.last().reels)
    }

    @Test
    fun `counts each surface separately`() {
        val events = listOf(
            event(at(today, 9), Surface.REELS),
            event(at(today, 10), Surface.REELS),
            event(at(today, 11), Surface.EXPLORE),
        )

        val last = dailyCounts(events, paris, today, days = 14).last()

        assertEquals(2, last.reels)
        assertEquals(1, last.explore)
    }

    @Test
    fun `buckets by local day not by utc day`() {
        // 00:30 Paris on the 16th is 22:30 UTC on the 15th.
        val events = listOf(event(at(today, 0, 30), Surface.REELS))

        val counts = dailyCounts(events, paris, today, days = 14)

        assertEquals(1, counts.last().reels)
        assertEquals(0, counts[counts.size - 2].reels)
    }

    @Test
    fun `ignores events older than the window`() {
        val events = listOf(event(at(today.minusDays(20), 12), Surface.REELS))

        assertEquals(0, dailyCounts(events, paris, today, days = 14).sumOf { it.reels })
    }

    @Test
    fun `ignores an unknown surface name without crashing`() {
        val events = listOf(BlockEvent(epochMillis = at(today, 9), surface = "STORIES", ruleTier = "HIGH"))

        val last = dailyCounts(events, paris, today, days = 14).last()

        assertEquals(0, last.reels)
        assertEquals(0, last.explore)
    }
}
