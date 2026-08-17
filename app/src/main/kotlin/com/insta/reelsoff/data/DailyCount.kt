package com.insta.reelsoff.data

import com.insta.detection.Surface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyCount(
    val date: LocalDate,
    val reels: Int,
    val explore: Int,
    val shorts: Int = 0,
    val spotlight: Int = 0,
) {
    val total: Int get() = reels + explore + shorts + spotlight
}

/**
 * Buckets events into local days, oldest first, including empty days so the
 * chart keeps a stable width.
 *
 * Done in Kotlin rather than SQL because local-day boundaries depend on the
 * time zone, which java.time handles and a SQLite date expression does not.
 */
fun dailyCounts(
    events: List<BlockEvent>,
    zone: ZoneId,
    today: LocalDate,
    days: Int,
): List<DailyCount> {
    val firstDay = today.minusDays((days - 1).toLong())
    val reels = mutableMapOf<LocalDate, Int>()
    val explore = mutableMapOf<LocalDate, Int>()
    val shorts = mutableMapOf<LocalDate, Int>()
    val spotlight = mutableMapOf<LocalDate, Int>()

    for (event in events) {
        val date = Instant.ofEpochMilli(event.epochMillis).atZone(zone).toLocalDate()
        if (date < firstDay || date > today) continue
        when (event.surface) {
            Surface.REELS.name -> reels.merge(date, 1, Int::plus)
            Surface.EXPLORE.name -> explore.merge(date, 1, Int::plus)
            Surface.SHORTS.name -> shorts.merge(date, 1, Int::plus)
            Surface.SPOTLIGHT.name -> spotlight.merge(date, 1, Int::plus)
            else -> Unit
        }
    }

    return (0 until days).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        DailyCount(date, reels[date] ?: 0, explore[date] ?: 0, shorts[date] ?: 0, spotlight[date] ?: 0)
    }
}
