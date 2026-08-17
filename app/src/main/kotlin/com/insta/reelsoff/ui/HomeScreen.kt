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
            Callout(stringResource(R.string.rules_load_warning, ruleLoadError))
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
            HistoryChart(state.history)
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

        Section(title = stringResource(R.string.maintenance_title)) {
            // Both of these only matter when something has gone wrong, so they sit at
            // the foot of the page rather than above the numbers the user came for.
            Text(
                text = stringResource(R.string.battery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
            Spacer(Modifier.height(10.dp))
            // state.declaredPackages is what the service last actually assigned to
            // serviceInfo.packageNames, published by InstagramWatcherService itself
            // right after a successful assignment (see DeclaredPackages.kt and
            // applyDeclaredPackages) — not recomputed here from the rule set and not
            // filtered by the installed-app detection the `groups` above use. That
            // makes this line true of what happened, not of what should have
            // happened: it cannot claim success for an assignment that threw, and it
            // cannot go stale relative to a hand-edited rules.json override the way a
            // ViewModel-side recomputation could.
            val observedLabels = state.declaredPackages
                .mapNotNull { packageName -> labelForPackage(packageName) }
                .map { stringResource(it) }
                .distinct()
                .sorted()
            Text(
                text = stringResource(
                    R.string.declared_packages,
                    if (observedLabels.isEmpty()) "—" else observedLabels.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = EncreDouce,
            )
            Spacer(Modifier.height(14.dp))
            CaptureControl(
                status = state.captureStatus,
                serviceEnabled = state.serviceEnabled,
                onStartCapture = onStartCapture,
            )
        }
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

/** A titled band: hairline, small-caps heading, then the content. */
@Composable
private fun Section(
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
 * A repair tool, not a daily control: it recalibrates the rules when Instagram
 * drifts. Kept reachable, but given the least weight on the page.
 *
 * It used to be a bare button whose only feedback was a line in logcat, so the
 * three ways it can quietly do nothing — service off, never reaching Instagram,
 * window already over — all looked exactly like a working press.
 */
@Composable
private fun CaptureControl(
    status: CaptureStatus,
    serviceEnabled: Boolean,
    onStartCapture: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val phase = capturePhase(status, now)

    // Ticks only while the display can still change, and stops on its own: a
    // permanent one-second timer to render a finished session would be waste.
    LaunchedEffect(status) {
        while (true) {
            now = System.currentTimeMillis()
            val current = capturePhase(status, now)
            if (current != CapturePhase.WAITING && current != CapturePhase.RUNNING) break
            delay(1_000)
        }
    }

    val message = when {
        !serviceEnabled -> stringResource(R.string.capture_needs_service)
        phase == CapturePhase.WAITING -> stringResource(R.string.capture_waiting)
        phase == CapturePhase.RUNNING -> stringResource(
            R.string.capture_running,
            remainingSeconds(status, now),
            status.count,
        )
        phase == CapturePhase.DONE -> stringResource(R.string.capture_done, status.count)
        phase == CapturePhase.MISSED -> stringResource(R.string.capture_missed)
        else -> stringResource(R.string.capture_hint)
    }

    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (phase == CapturePhase.RUNNING) Accent else EncreDouce,
    )

    // No button mid-session: pressing it would silently restart the window the
    // user is already watching count down.
    if (phase != CapturePhase.WAITING && phase != CapturePhase.RUNNING) {
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onStartCapture,
            enabled = serviceEnabled,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(
                    if (phase == CapturePhase.IDLE) R.string.start_capture else R.string.restart_capture,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (serviceEnabled) Accent else EncreDouce,
            )
        }
    }
}

/** A warning, set as a marginal rule rather than a filled box. */
@Composable
private fun Callout(text: String) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(Alerte),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Encre,
        )
    }
}

@Composable
private fun TodayTotal(total: Int, today: DailyCount?) {
    val accessibleTotal = stringResource(R.string.today_total, total)
    Column {
        Counter(
            value = total,
            label = stringResource(R.string.today),
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
private fun HistoryChart(history: List<DailyCount>) {
    val maximum = (history.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
    val lastIndex = history.lastIndex

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            history.forEachIndexed { index, day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((100.dp * day.total / maximum).coerceAtLeast(2.dp))
                        .background(
                            when {
                                index == lastIndex -> Accent
                                day.total == 0 -> Filet
                                else -> Encre
                            },
                        ),
                )
            }
        }
        // The bars stand on this line, so a day with no blocks reads as a zero
        // rather than as missing data.
        HorizontalDivider(thickness = 1.dp, color = Encre)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            history.forEachIndexed { index, day ->
                Text(
                    text = day.date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, Locale.FRENCH),
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
