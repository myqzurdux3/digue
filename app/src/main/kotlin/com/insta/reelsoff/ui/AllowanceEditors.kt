package com.insta.reelsoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.service.AllowanceSettings
import java.util.Locale

/** Offered daily budgets. Five minutes is the default and the middle choice. */
val QUOTA_CHOICES: List<Long> = listOf(60_000, 300_000, 600_000, 900_000, 1_800_000)

/**
 * Offered cooldowns. Zero comes first because that is where a fresh install
 * sits: the settings have to be arrangeable before the lock is armed, and
 * choosing any nonzero delay is a tightening, so it lands at once and locks
 * everything after it.
 */
val COOLDOWN_CHOICES: List<Long> = listOf(0, 3_600_000, 6 * 3_600_000, 24 * 3_600_000, 72 * 3_600_000)

private const val WINDOW_STEP_MINUTES = 15

/**
 * Moves a minute-of-day by [delta], wrapping at midnight in both directions.
 *
 * Kotlin's `%` keeps the sign of the dividend, so stepping back from 00:00 would
 * land on a negative minute and every window comparison downstream would quietly
 * stop matching. The extra `+ 1440` is what prevents that.
 */
fun stepMinute(minuteOfDay: Int, delta: Int): Int = ((minuteOfDay + delta) % 1440 + 1440) % 1440

@Composable
fun AllowanceEditors(
    state: AllowanceUiState,
    onPropose: (AllowanceSettings) -> Unit,
) {
    // Rebuilt from what is actually in force, so an editor never proposes a
    // change relative to a value the lock has already superseded.
    val current = AllowanceSettings(
        enabled = state.enabled,
        quotaMillis = state.quotaMillis,
        windowStartMinutes = state.windowStartMinutes,
        windowEndMinutes = state.windowEndMinutes,
        cooldownMillis = state.cooldownMillis,
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.allowance_enable),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Encre,
            )
            Switch(
                checked = state.enabled,
                onCheckedChange = { onPropose(current.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Papier,
                    checkedTrackColor = Accent,
                    checkedBorderColor = Accent,
                    uncheckedThumbColor = EncreDouce,
                    uncheckedTrackColor = Papier,
                    uncheckedBorderColor = EncreDouce,
                ),
            )
        }

        Spacer(Modifier.height(18.dp))
        ChoiceRow(
            label = stringResource(R.string.allowance_quota_label),
            choices = QUOTA_CHOICES,
            selected = state.quotaMillis,
            onSelect = { onPropose(current.copy(quotaMillis = it)) },
        )

        Spacer(Modifier.height(18.dp))
        Label(stringResource(R.string.allowance_window_label))
        Spacer(Modifier.height(8.dp))
        MinuteStepper(
            text = stringResource(R.string.allowance_window_from, formatMinuteOfDay(state.windowStartMinutes)),
            onStep = { onPropose(current.copy(windowStartMinutes = stepMinute(state.windowStartMinutes, it))) },
        )
        Spacer(Modifier.height(6.dp))
        MinuteStepper(
            text = stringResource(R.string.allowance_window_to, formatMinuteOfDay(state.windowEndMinutes)),
            onStep = { onPropose(current.copy(windowEndMinutes = stepMinute(state.windowEndMinutes, it))) },
        )

        Spacer(Modifier.height(18.dp))
        ChoiceRow(
            label = stringResource(R.string.allowance_cooldown_label),
            choices = COOLDOWN_CHOICES,
            selected = state.cooldownMillis,
            onSelect = { onPropose(current.copy(cooldownMillis = it)) },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(Locale.FRENCH),
        style = MaterialTheme.typography.labelSmall,
        color = EncreDouce,
    )
}

/** Presets as plain text, the one in force inked and the rest soft. */
@Composable
private fun ChoiceRow(
    label: String,
    choices: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    Label(label)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (choice in choices) {
            TextButton(
                onClick = { onSelect(choice) },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    // Zero is a cooldown choice, and "0 s" would read as a
                    // duration rather than as the absence of one.
                    text = if (choice == 0L) {
                        stringResource(R.string.allowance_cooldown_none)
                    } else {
                        formatChoice(choice)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (choice == selected) Accent else EncreDouce,
                )
            }
        }
    }
}

@Composable
private fun MinuteStepper(text: String, onStep: (Int) -> Unit) {
    val earlier = stringResource(R.string.allowance_earlier)
    val later = stringResource(R.string.allowance_later)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Encre,
        )
        // The glyphs are a minus sign and a plus sign; a screen reader would
        // announce them as punctuation, so each button carries its own wording.
        TextButton(
            onClick = { onStep(-WINDOW_STEP_MINUTES) },
            modifier = Modifier.semantics { contentDescription = earlier },
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text("−", style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
        TextButton(
            onClick = { onStep(WINDOW_STEP_MINUTES) },
            modifier = Modifier.semantics { contentDescription = later },
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text("+", style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
    }
}
