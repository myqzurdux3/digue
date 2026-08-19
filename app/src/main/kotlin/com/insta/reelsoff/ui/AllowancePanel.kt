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
                    formatMinuteOfDay(state.windowStartMinutes, currentLocale()),
                    formatMinuteOfDay(state.windowEndMinutes, currentLocale()),
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
            // At most one change is ever held, and the editors below always show
            // what is in force rather than what is waiting — so a second tap
            // proposes from the current value again and supersedes the first.
            // That is the model working as designed, and it is invisible unless
            // it is said: without this line, stepping the window twice looks like
            // the app losing the first press.
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.allowance_pending_detail),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
        }

        // Always shown, including when the quota is off — the switch that turns
        // it back on lives in here.
        Spacer(Modifier.height(22.dp))
        AllowanceEditors(state = state, onPropose = onPropose)
    }
}
