package com.insta.reelsoff.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Cuts a log into local days, oldest first, always exactly [days] entries long —
 * empty days included, so a chart keeps a stable width and a day with nothing in
 * it reads as a zero rather than as missing data.
 *
 * Extracted because [dailyCounts] and [dailyWatched] were the same loop written
 * twice: same `firstDay`, same epoch-to-local-day conversion, same rejection of
 * anything outside the window, same filling of the gaps. Only the aggregation
 * differed, and that is what [build] now carries.
 *
 * It is not only about repetition. The two results are read **index for index**
 * by the history chart, and that alignment is a property of these bounds. Sharing
 * one implementation makes it structural instead of a coincidence that has to be
 * maintained in two places — and there is a test that says so.
 *
 * Done in Kotlin rather than SQL because local-day boundaries depend on the time
 * zone, which java.time handles and a SQLite date expression does not.
 */
internal fun <T, R> bucketByDay(
    events: List<T>,
    zone: ZoneId,
    today: LocalDate,
    days: Int,
    epochMillisOf: (T) -> Long,
    build: (LocalDate, List<T>) -> R,
): List<R> {
    val firstDay = today.minusDays((days - 1).toLong())
    val perDay = mutableMapOf<LocalDate, MutableList<T>>()

    for (event in events) {
        val date = Instant.ofEpochMilli(epochMillisOf(event)).atZone(zone).toLocalDate()
        if (date < firstDay || date > today) continue
        perDay.getOrPut(date) { mutableListOf() }.add(event)
    }

    return (0 until days).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        build(date, perDay[date].orEmpty())
    }
}
