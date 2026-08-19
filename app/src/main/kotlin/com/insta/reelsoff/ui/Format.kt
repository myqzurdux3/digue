package com.insta.reelsoff.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The locale the interface is currently drawn in.
 *
 * Not [Locale.getDefault]: Android 13+ lets the user pick a language for this
 * app alone, and that choice shows up in the configuration, not in the default
 * locale.
 */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

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
 * The units that do change from one language to the next.
 *
 * Everything the user reads lives in `strings.xml`; these do not, because the
 * formatters below are pure functions with JVM tests asserting their exact
 * output, and taking a `Context` would end that. The set is deliberately tiny:
 * `h`, `min` and `s` are the same symbols in French and in English, so only the
 * clock separator and the byte units earn a line here. A third language means
 * adding a branch to [unitsFor] as well as a `values-xx/strings.xml`.
 */
private data class Units(
    /** Two positional arguments: hours, then minutes. */
    val timeOfDay: String,
    val bytes: String,
    val kilobytes: String,
    val megabytes: String,
)

private val FRENCH_UNITS = Units(timeOfDay = "%02d h %02d", bytes = "o", kilobytes = "ko", megabytes = "Mo")
private val ENGLISH_UNITS = Units(timeOfDay = "%02d:%02d", bytes = "B", kilobytes = "kB", megabytes = "MB")

/**
 * English for every language that is not French — the same rule the resource
 * folders follow, `values/` being English and `values-fr/` French.
 *
 * Compared on [Locale.getLanguage] rather than on the locale itself: `fr-CA`
 * and `fr-BE` are French too.
 */
private fun unitsFor(locale: Locale): Units =
    if (locale.language == Locale.FRENCH.language) FRENCH_UNITS else ENGLISH_UNITS

/**
 * A duration at the coarsest unit that still says something useful: seconds
 * under a minute, minutes and seconds under an hour, hours and minutes above.
 *
 * Takes no locale because it needs none — `h`, `min` and `s` read the same in
 * both languages, and there is no decimal separator to place. [Locale.ROOT]
 * only pins the digits to ASCII.
 */
fun formatDuration(millis: Long): String {
    val (hours, minutes, seconds) = split(millis)
    return when {
        hours > 0 -> String.format(Locale.ROOT, "%d h %02d", hours, minutes)
        minutes > 0 -> String.format(Locale.ROOT, "%d min %02d s", minutes, seconds)
        else -> String.format(Locale.ROOT, "%d s", seconds)
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
        hours > 0 && minutes == 0L -> String.format(Locale.ROOT, "%d h", hours)
        hours > 0 -> String.format(Locale.ROOT, "%d h %02d", hours, minutes)
        else -> String.format(Locale.ROOT, "%d min", minutes)
    }
}

/** Minutes since local midnight, as a wall-clock time. */
fun formatMinuteOfDay(minuteOfDay: Int, locale: Locale): String =
    String.format(locale, unitsFor(locale).timeOfDay, minuteOfDay / 60, minuteOfDay % 60)

/**
 * A file size at the coarsest unit that still says something useful.
 *
 * Rounds to one decimal above a kilobyte, because the figure exists to tell the
 * user whether a directory is worth emptying, not to be exact. The locale
 * places the decimal mark as well as naming the unit: a comma in French, a full
 * stop in English.
 */
fun formatBytes(bytes: Long, locale: Locale): String {
    val safe = bytes.coerceAtLeast(0)
    val units = unitsFor(locale)
    return when {
        safe >= 1_000_000 -> String.format(locale, "%.1f ${units.megabytes}", safe / 1_000_000.0)
        safe >= 1_000 -> String.format(locale, "%.1f ${units.kilobytes}", safe / 1_000.0)
        else -> String.format(locale, "%d ${units.bytes}", safe)
    }
}
