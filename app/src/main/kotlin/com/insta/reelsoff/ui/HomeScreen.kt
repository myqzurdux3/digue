package com.insta.reelsoff.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insta.detection.Surface
import com.insta.reelsoff.R
import com.insta.reelsoff.data.CaptureStatus
import com.insta.reelsoff.data.DailyCount
import com.insta.reelsoff.service.AllowanceSettings
import kotlinx.coroutines.delay
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

private val PAGE_MARGIN = 28.dp

@Composable
fun HomeScreen(
    state: HomeUiState,
    allowance: AllowanceUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onStartCapture: () -> Unit,
    onSurfaceBlockedChanged: (Surface, Boolean) -> Unit,
    onOpenPass: () -> Unit,
    onClosePass: () -> Unit,
    onCancelPendingChange: () -> Unit,
    onProposeAllowance: (AllowanceSettings) -> Unit,
    onReloadRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // The caller's modifier goes first so window-inset padding sits outside the
        // scroll container: applied inside, the content would scroll under the bars.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_MARGIN)
            .padding(top = 36.dp, bottom = 48.dp),
    ) {
        Wordmark()

        Spacer(Modifier.height(36.dp))
        ServiceBlock(state.serviceEnabled, onOpenAccessibilitySettings)

        // The two causes need different user actions, so they get different wording:
        // a rule-load failure names the reason and points at rules.json; a run of
        // fallback-tier blocks is Instagram having drifted and needing new rules.
        val ruleLoadError = state.ruleLoadError
        if (ruleLoadError != null) {
            Spacer(Modifier.height(20.dp))
            Callout(stringResource(R.string.rules_load_warning)) {
                // The diagnostic is an English technical string from the loader.
                // It sits on its own line, framed as a detail, rather than being
                // interpolated mid-sentence into French prose.
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rules_load_detail, ruleLoadError),
                    style = MaterialTheme.typography.bodySmall,
                    color = EncreDouce,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onReloadRules,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.reload_rules),
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
            }
        } else if (state.degraded) {
            Spacer(Modifier.height(20.dp))
            Callout(stringResource(R.string.degraded_warning))
        }

        // Above the counters: it is the one thing on this screen that is acted
        // on rather than read, and its countdown is time-critical.
        Section(title = stringResource(R.string.allowance_title)) {
            AllowancePanel(
                state = allowance,
                serviceEnabled = state.serviceEnabled,
                onOpen = onOpenPass,
                onClose = onClosePass,
                onCancelPending = onCancelPendingChange,
                onPropose = onProposeAllowance,
            )
        }

        Section(title = stringResource(R.string.today)) {
            TodayTotal(state.todayTotal, state.history.lastOrNull())
            // The count says how often the app caught you; this says how long you
            // watched anyway. The second is the figure the quota exists to move,
            // so it sits with the first rather than in a corner.
            if (state.todayWatchedMillis > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.today_watched,
                        formatDuration(state.todayWatchedMillis),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = EncreDouce,
                )
            }
        }

        Section(
            title = stringResource(R.string.history_title),
            trailing = stringResource(R.string.history_total, state.history.sumOf { it.total }),
        ) {
            HistoryChart(state.history, state.watched)
            if (state.watchedTotalMillis > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.history_watched,
                        formatDuration(state.watchedTotalMillis),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = EncreDouce,
                )
            }
        }

        val groups = surfaceGroups(installed = state.installedPackages)
        for (group in groups) {
            Section(title = stringResource(group.labelResId)) {
                group.surfaces.forEachIndexed { index, surface ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        label = switchLabel(surface),
                        checked = surface in state.settings.blockedSurfaces,
                        onCheckedChange = { onSurfaceBlockedChanged(surface, it) },
                    )
                }
            }
        }

        MaintenanceSection(state, onStartCapture)
    }
}

/**
 * Which switch label a surface's toggle carries, inside its app's [Section].
 *
 * `OTHER` falls back to an empty string rather than throwing: it never actually
 * reaches here (`surfaceGroups()` never emits it), but this screen shares a
 * process with the accessibility service, so a wrong label beats a crash.
 */
@Composable
private fun switchLabel(surface: Surface): String = when (surface) {
    Surface.REELS -> stringResource(R.string.block_reels)
    Surface.EXPLORE -> stringResource(R.string.block_explore)
    Surface.SHORTS -> stringResource(R.string.block_shorts)
    Surface.SPOTLIGHT -> stringResource(R.string.block_spotlight)
    Surface.DISCOVER -> stringResource(R.string.block_discover)
    Surface.OTHER -> ""
}

/**
 * The short display name for a surface, used in the daily breakdown line.
 * `OTHER` is unreachable (see [switchLabel]) and falls back the same way.
 */
@Composable
private fun shortLabel(surface: Surface): String = when (surface) {
    Surface.REELS -> stringResource(R.string.reels)
    Surface.EXPLORE -> stringResource(R.string.explore)
    Surface.SHORTS -> stringResource(R.string.shorts)
    Surface.SPOTLIGHT -> stringResource(R.string.spotlight)
    Surface.DISCOVER -> stringResource(R.string.discover)
    Surface.OTHER -> ""
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_digue),
            contentDescription = null, // decorative: the name follows as text
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(R.string.app_name).uppercase(Locale.FRENCH),
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 4.sp),
                color = Encre,
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
        }
    }
}

/**
 * A titled band: hairline, small-caps heading, then the content.
 *
 * Internal rather than private: the maintenance panel lives in its own file and
 * has to sit in the same band as every other section.
 */
@Composable
internal fun Section(
    title: String,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(32.dp))
    HorizontalDivider(thickness = 1.dp, color = Filet)
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(Locale.FRENCH),
            style = MaterialTheme.typography.labelSmall,
            color = EncreDouce,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = EncreDouce,
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    content()
}

@Composable
private fun ServiceBlock(enabled: Boolean, onOpenSettings: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(if (enabled) Accent else Alerte, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(if (enabled) R.string.service_on else R.string.service_off),
                style = MaterialTheme.typography.titleMedium,
                color = Encre,
            )
        }
        if (!enabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.service_off_detail),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(R.string.open_accessibility_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Encre,
                )
            }
        }
    }
}

/**
 * A warning, set as a marginal rule rather than a filled box.
 *
 * [extra] carries whatever the reader can do about it — a technical detail, an
 * action — inside the same rule, so the remedy is not separated from the problem.
 */
@Composable
private fun Callout(text: String, extra: @Composable (() -> Unit)? = null) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(Alerte),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Encre,
            )
            extra?.invoke()
        }
    }
}

@Composable
private fun TodayTotal(total: Int, today: DailyCount?) {
    val accessibleTotal = stringResource(R.string.today_total, total)
    Column {
        Counter(
            value = total,
            // No label: the enclosing Section is already titled "Aujourd'hui",
            // and repeating it under the figure read as a stutter on the device.
            label = stringResource(R.string.today_blocks),
            // One utterance ("Retenu 3 fois") rather than a screen reader stitching
            // together the bare number and the small-caps label separately.
            modifier = Modifier.clearAndSetSemantics { contentDescription = accessibleTotal },
        )
        // Every surface with a nonzero count today, blocked or not right now — see
        // breakdownSurfaces() — so this line's numbers always sum to the total above.
        // Enum order, which is also the order surfaces are declared in the switch
        // sections below, so the breakdown reads left to right like the toggles do.
        val breakdown = breakdownSurfaces(today)
            .map { surface -> shortLabel(surface) to (today?.countFor(surface) ?: 0) }
        if (breakdown.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = breakdown.joinToString(" · ") { (label, count) -> "$label : $count" },
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
        }
    }
}

@Composable
private fun Counter(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = if (value == 0) EncreDouce else Encre,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label.uppercase(Locale.FRENCH),
            style = MaterialTheme.typography.labelSmall,
            color = EncreDouce,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Encre,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
}
