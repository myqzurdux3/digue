package com.insta.reelsoff.ui

import java.util.Locale

/** Hours, whole minutes past the hour, whole seconds past the minute. */
private data class Split(val hours: Long, val minutes: Long, val seconds: Long)

/**
 * Always rounds **down**, at every unit: a countdown must never claim more time
 * than is actually left.
 */
private fun split(millis: Long): Split {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return Split(
        hours = totalSeconds / 3600,
        minutes = (totalSeconds % 3600) / 60,
        seconds = totalSeconds % 60,
    )
}

/**
 * A duration in French, at the coarsest unit that still says something useful:
 * seconds under a minute, minutes and seconds under an hour, hours and minutes
 * above.
 */
fun formatDuration(millis: Long): String {
    val (hours, minutes, seconds) = split(millis)
    return when {
        hours > 0 -> String.format(Locale.FRENCH, "%d h %02d", hours, minutes)
        minutes > 0 -> String.format(Locale.FRENCH, "%d min %02d s", minutes, seconds)
        else -> String.format(Locale.FRENCH, "%d s", seconds)
    }
}

/**
 * A preset's label: the same duration with nothing that is always zero.
 *
 * [formatDuration] is right for a countdown, where the seconds move, and wrong
 * for a row of five presets — "30 min 00 s" does not fit the width and Compose
 * wraps it to one glyph per line, 16 px wide and 293 tall. Measured on the
 * device, not guessed.
 */
fun formatChoice(millis: Long): String {
    val (hours, minutes, _) = split(millis)
    return when {
        hours > 0 && minutes == 0L -> String.format(Locale.FRENCH, "%d h", hours)
        hours > 0 -> String.format(Locale.FRENCH, "%d h %02d", hours, minutes)
        else -> String.format(Locale.FRENCH, "%d min", minutes)
    }
}

/** Minutes since local midnight, as a wall-clock time. */
fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.FRENCH, "%02d h %02d", minuteOfDay / 60, minuteOfDay % 60)

/**
 * A file size in French, at the coarsest unit that still says something useful.
 *
 * Rounds to one decimal above a kilobyte, because the figure exists to tell the
 * user whether a directory is worth emptying, not to be exact.
 */
fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe >= 1_000_000 -> String.format(Locale.FRENCH, "%.1f Mo", safe / 1_000_000.0)
        safe >= 1_000 -> String.format(Locale.FRENCH, "%.1f ko", safe / 1_000.0)
        else -> String.format(Locale.FRENCH, "%d o", safe)
    }
}
