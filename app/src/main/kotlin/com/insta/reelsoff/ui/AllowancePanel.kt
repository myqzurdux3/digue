package com.insta.reelsoff.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.service.AllowanceSettings
import java.util.Locale

/**
 * A duration in French, at the coarsest unit that still says something useful:
 * seconds under a minute, minutes and seconds under an hour, hours and minutes
 * above. Always rounds **down**, so a countdown never claims more time than is
 * actually left.
 */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
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
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 && minutes == 0L -> String.format(Locale.FRENCH, "%d h", hours)
        hours > 0 -> String.format(Locale.FRENCH, "%d h %02d", hours, minutes)
        else -> String.format(Locale.FRENCH, "%d min", minutes)
    }
}

/** Minutes since local midnight, as a wall-clock time. */
fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.FRENCH, "%02d h %02d", minuteOfDay / 60, minuteOfDay % 60)

@Composable
fun AllowancePanel(
    state: AllowanceUiState,
    serviceEnabled: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onCancelPending: () -> Unit,
    onPropose: (AllowanceSettings) -> Unit,
) {
    // No local ticker here: HomeViewModel.allowance recomputes every second, so
    // the numbers arriving in `state` already move. An earlier version ticked
    // here instead and the countdown still froze — recomposing was redrawing the
    // same stale state.
    Column {
        if (state.enabled) {
            val headline = when {
                state.passRunning ->
                    stringResource(R.string.allowance_running, formatDuration(state.remainingMillis))
                state.remainingMillis <= 0 -> stringResource(R.string.allowance_exhausted)
                else -> stringResource(
                    R.string.allowance_remaining,
                    formatDuration(state.remainingMillis),
                    formatDuration(state.quotaMillis),
                )
            }
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                color = if (state.passRunning) Accent else Encre,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (state.insideWindow) R.string.allowance_window else R.string.allowance_outside_window,
                    formatMinuteOfDay(state.windowStartMinutes),
                    formatMinuteOfDay(state.windowEndMinutes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )

            if (!serviceEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.allowance_needs_service),
                    style = MaterialTheme.typography.bodySmall,
                    color = EncreDouce,
                )
            }

            Spacer(Modifier.height(14.dp))
            if (state.passRunning) {
                OutlinedButton(onClick = onClose, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = stringResource(R.string.allowance_close),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Encre,
                    )
                }
            } else {
                val openable = state.canOpen && serviceEnabled
                OutlinedButton(
                    onClick = onOpen,
                    enabled = openable,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(R.string.allowance_open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (openable) Encre else EncreDouce,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.allowance_off),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            // A zero cooldown is not "waits 0 s" — it is the lock not yet armed,
            // and saying so is what tells the user which gesture arms it.
            text = if (state.cooldownMillis == 0L) {
                stringResource(R.string.allowance_unlocked)
            } else {
                stringResource(R.string.allowance_lock_hint, formatDuration(state.cooldownMillis))
            },
            style = MaterialTheme.typography.bodySmall,
            color = EncreDouce,
        )

        val pendingIn = state.pendingInMillis
        if (pendingIn != null) {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.allowance_pending, formatDuration(pendingIn)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Alerte,
                )
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = onCancelPending,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.allowance_cancel_pending),
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
            }
        }

        // Always shown, including when the quota is off — the switch that turns
        // it back on lives in here.
        Spacer(Modifier.height(22.dp))
        AllowanceEditors(state = state, onPropose = onPropose)
    }
}
