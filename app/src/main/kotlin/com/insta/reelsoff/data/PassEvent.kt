package com.insta.reelsoff.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One scrolling pass that ended, and how long it ran.
 *
 * A separate table from [BlockEvent] rather than a synthetic surface in it: that
 * table's rows are counted into the daily chart, and a row that is not a block
 * would inflate every figure the user reads.
 *
 * [epochMillis] is when the pass **closed**, not when it opened. A pass is only
 * worth recording once its duration is known, and bucketing by close time keeps
 * one row in exactly one day — the alternative, splitting a pass that straddles
 * midnight across two days, buys nothing since a pass is shut at midnight anyway.
 */
@Entity(tableName = "pass_event")
data class PassEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val durationMillis: Long,
)

/** One day's watched time. Days with no pass read as zero, not as missing. */
data class DailyWatched(
    val date: LocalDate,
    val millis: Long = 0,
)

/**
 * Buckets passes into local days, oldest first, including empty days so the
 * figures line up with [dailyCounts] index for index.
 *
 * Same shape and same reason as [dailyCounts]: local-day boundaries depend on
 * the time zone, which java.time handles and a SQLite date expression does not.
 */
fun dailyWatched(
    events: List<PassEvent>,
    zone: ZoneId,
    today: LocalDate,
    days: Int,
): List<DailyWatched> {
    val firstDay = today.minusDays((days - 1).toLong())
    val perDay = mutableMapOf<LocalDate, Long>()

    for (event in events) {
        val date = Instant.ofEpochMilli(event.epochMillis).atZone(zone).toLocalDate()
        if (date < firstDay || date > today) continue
        // A negative duration cannot happen from closePass, which floors its
        // elapsed time at zero — but the database outlives any one build, so a row
        // written by a version that did not is dropped rather than subtracted.
        if (event.durationMillis <= 0) continue
        perDay.merge(date, event.durationMillis, Long::plus)
    }

    return (0 until days).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        DailyWatched(date, perDay[date] ?: 0)
    }
}
