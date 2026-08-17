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
        val day = DailyCount(today)

        assertTrue(breakdownSurfaces(day).isEmpty())
    }

    @Test
    fun `a surface with events shows even after being switched off`() {
        // The switch and the day's log are two different things: a surface that
        // logged blocks earlier today, then got toggled off, must still be visible
        // here or the breakdown stops adding up to the total shown above it.
        val day = DailyCount(today, mapOf(Surface.REELS to 3))

        assertEquals(listOf(Surface.REELS), breakdownSurfaces(day))
    }

    @Test
    fun `every listed surface's count sums to the day's total`() {
        val day = DailyCount(today, mapOf(Surface.REELS to 5, Surface.SHORTS to 2, Surface.SPOTLIGHT to 1))

        val sum = breakdownSurfaces(day).sumOf { day.countFor(it) }

        assertEquals(day.total, sum)
    }

    @Test
    fun `order follows the surface enum, not insertion order`() {
        val day = DailyCount(today, mapOf(Surface.REELS to 1, Surface.EXPLORE to 1, Surface.SHORTS to 1, Surface.SPOTLIGHT to 1))

        assertEquals(
            listOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS, Surface.SPOTLIGHT),
            breakdownSurfaces(day),
        )
    }
}
