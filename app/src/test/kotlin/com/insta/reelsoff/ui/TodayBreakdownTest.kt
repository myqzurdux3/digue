package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.data.DailyCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodayBreakdownTest {

    private val today = LocalDate.of(2026, 8, 17)

    @Test
    fun `no day means no breakdown`() {
        assertTrue(breakdownSurfaces(null).isEmpty())
    }

    @Test
    fun `a surface with a zero count is left out`() {
        val day = DailyCount(today, reels = 0, explore = 0, shorts = 0, spotlight = 0)

        assertTrue(breakdownSurfaces(day).isEmpty())
    }

    @Test
    fun `a surface with events shows even after being switched off`() {
        // The switch and the day's log are two different things: a surface that
        // logged blocks earlier today, then got toggled off, must still be visible
        // here or the breakdown stops adding up to the total shown above it.
        val day = DailyCount(today, reels = 3, explore = 0, shorts = 0, spotlight = 0)

        assertEquals(listOf(Surface.REELS), breakdownSurfaces(day))
    }

    @Test
    fun `every listed surface's count sums to the day's total`() {
        val day = DailyCount(today, reels = 5, explore = 0, shorts = 2, spotlight = 1)

        val sum = breakdownSurfaces(day).sumOf { day.countFor(it) }

        assertEquals(day.total, sum)
    }

    @Test
    fun `order follows the surface enum, not insertion order`() {
        val day = DailyCount(today, reels = 1, explore = 1, shorts = 1, spotlight = 1)

        assertEquals(
            listOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS, Surface.SPOTLIGHT),
            breakdownSurfaces(day),
        )
    }
}
