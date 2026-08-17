package com.insta.reelsoff.data

import androidx.room.Entity
import androidx.room.PrimaryKey
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
 * figures line up with [dailyCounts] index for index — which they do by
 * construction, both going through [bucketByDay].
 */
fun dailyWatched(
    events: List<PassEvent>,
    zone: ZoneId,
    today: LocalDate,
    days: Int,
): List<DailyWatched> =
    bucketByDay(events, zone, today, days, PassEvent::epochMillis) { date, ofThatDay ->
        DailyWatched(
            date = date,
            // A negative duration cannot happen from closePass, which floors its
            // elapsed time at zero — but the database outlives any one build, so a
            // row written by a version that did not is dropped rather than
            // subtracted.
            millis = ofThatDay.filter { it.durationMillis > 0 }.sumOf { it.durationMillis },
        )
    }
