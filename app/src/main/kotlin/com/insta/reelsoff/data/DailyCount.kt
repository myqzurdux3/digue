package com.insta.reelsoff.data

import com.insta.detection.Surface
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day's blocks, counted per surface.
 *
 * A map rather than one field per surface: the field-per-surface shape needed an
 * edit in four places for every app added — the data class, the bucket, the
 * constructor call and the `when` that read it back — and forgetting any one of
 * them made that surface's blocks vanish from the chart without a word. Surfaces
 * absent from the map simply read as zero.
 */
data class DailyCount(
    val date: LocalDate,
    val counts: Map<Surface, Int> = emptyMap(),
) {
    val total: Int get() = counts.values.sum()

    /** `OTHER` always reads as 0: it is never tracked and never blocked. */
    fun countFor(surface: Surface): Int = counts[surface] ?: 0
}

/**
 * Buckets events into local days, oldest first, including empty days so the
 * chart keeps a stable width. The windowing itself lives in [bucketByDay], which
 * [dailyWatched] shares, so the two series line up index for index.
 */
fun dailyCounts(
    events: List<BlockEvent>,
    zone: ZoneId,
    today: LocalDate,
    days: Int,
): List<DailyCount> =
    bucketByDay(events, zone, today, days, BlockEvent::epochMillis) { date, ofThatDay ->
        val counts = mutableMapOf<Surface, Int>()
        for (event in ofThatDay) {
            // An unrecognised name is dropped rather than counted: the database
            // outlives any one build, so a row written by a newer version — or by
            // a surface since removed — must not break the chart.
            val surface = Surface.entries
                .firstOrNull { it.name == event.surface && it != Surface.OTHER }
                ?: continue
            counts.merge(surface, 1, Int::plus)
        }
        DailyCount(date, counts)
    }
