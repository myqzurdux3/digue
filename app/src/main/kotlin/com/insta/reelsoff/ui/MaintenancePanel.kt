package com.insta.reelsoff.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.data.CaptureStatus
import kotlinx.coroutines.delay

/**
 * The foot of the screen: everything that only matters once something has gone
 * wrong, or when the rules need recalibrating. Kept reachable, given the least
 * weight on the page, and kept out of HomeScreen so that file stays about the
 * screen's shape rather than its repair tools.
 */
@Composable
fun MaintenanceSection(state: HomeUiState, onStartCapture: () -> Unit) {
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

