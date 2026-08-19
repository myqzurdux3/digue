package com.insta.reelsoff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insta.reelsoff.R
import com.insta.reelsoff.data.DailyCount
import com.insta.reelsoff.data.DailyWatched
import java.time.format.TextStyle as JavaTextStyle

private val COLUMN_GAP = 5.dp

/**
 * Fourteen days, on two bands sharing one day axis.
 *
 * The upper band counts blocks — how often the app caught you. The lower one
 * measures the time watched inside a pass — how long you scrolled anyway. The
 * second is the figure the quota exists to move, and reading it against the
 * first is the whole point of showing them together.
 *
 * Deliberately **not** one band with two scales. Blocks are a count and watched
 * time is a duration; drawing them against a shared axis would invite comparing
 * numbers that have no common unit, and overlaying them at different scales
 * would let the eye read a crossing that means nothing. Two bands, each scaled
 * to its own maximum, each honest on its own.
 */
@Composable
fun HistoryChart(
    history: List<DailyCount>,
    watched: List<DailyWatched>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BandLabel(stringResource(R.string.chart_blocks))
        Spacer(Modifier.height(6.dp))
        Band(
            values = history.map { it.total.toLong() },
            heightDp = 104,
            lastColor = Accent,
            restColor = Encre,
        )
        // The bars stand on this line, so a day with no blocks reads as a zero
        // rather than as missing data.
        HorizontalDivider(thickness = 1.dp, color = Encre)

        // Only once there is something to show: an empty second band on a fresh
        // install would read as a broken chart rather than as an unused feature.
        if (watched.any { it.millis > 0 }) {
            Spacer(Modifier.height(18.dp))
            BandLabel(stringResource(R.string.chart_watched))
            Spacer(Modifier.height(6.dp))
            Band(
                values = watched.map { it.millis },
                heightDp = 44,
                lastColor = Accent,
                restColor = EncreDouce,
            )
            HorizontalDivider(thickness = 1.dp, color = Filet)
        }

        Spacer(Modifier.height(8.dp))
        DayLabels(history)
    }
}

@Composable
private fun BandLabel(text: String) {
    Text(
        text = text.uppercase(currentLocale()),
        style = MaterialTheme.typography.labelSmall,
        color = EncreDouce,
    )
}

/**
 * One row of bars, scaled to its own maximum.
 *
 * A zero-valued day still draws a 2dp stub: a column of nothing is
 * indistinguishable from a column that was never drawn, and the fourteen slots
 * have to stay legible as fourteen.
 */
@Composable
private fun Band(
    values: List<Long>,
    heightDp: Int,
    lastColor: Color,
    restColor: Color,
) {
    val maximum = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val lastIndex = values.lastIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((((heightDp - 4) * value.toFloat() / maximum).dp).coerceAtLeast(2.dp))
                    .background(
                        when {
                            index == lastIndex && value > 0 -> lastColor
                            value == 0L -> Filet
                            else -> restColor
                        },
                    ),
            )
        }
    }
}

@Composable
private fun DayLabels(history: List<DailyCount>) {
    val lastIndex = history.lastIndex
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        history.forEachIndexed { index, day ->
            Text(
                text = day.date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, currentLocale()),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                // Letter spacing is dropped here: on a single glyph it only shifts
                // the centred label off its bar.
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = if (index == lastIndex) Accent else EncreDouce,
            )
        }
    }
}
