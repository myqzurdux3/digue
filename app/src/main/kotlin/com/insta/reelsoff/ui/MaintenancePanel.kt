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
fun MaintenanceSection(
    state: HomeUiState,
    onStartCapture: () -> Unit,
    onDeleteCaptures: () -> Unit,
) {
    // The clock lives here rather than inside the capture control, because two
    // things below depend on the phase now: the capture button, and whether the
    // delete button may appear. Left where it was, `now` would have been local
    // state of one child and the other would have been drawn against whatever
    // time it happened to be composed at — a phase that changes by time passing
    // alone, with no emission to trigger a redraw. That is the frozen-countdown
    // defect this project has already paid for once.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val phase = capturePhase(state.captureStatus, now)
    val midSession = phase == CapturePhase.WAITING || phase == CapturePhase.RUNNING

    // Ticks only while the display can still change, and stops on its own: a
    // permanent one-second timer to render a finished session would be waste.
    LaunchedEffect(state.captureStatus) {
        while (true) {
            now = System.currentTimeMillis()
            val current = capturePhase(state.captureStatus, now)
            if (current != CapturePhase.WAITING && current != CapturePhase.RUNNING) break
            delay(1_000)
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
        // Reports what the service actually declared, not what it should have —
        // see the field's own documentation on HomeUiState for why that
        // distinction is the whole point of this line.
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
            phase = phase,
            now = now,
            serviceEnabled = state.serviceEnabled,
            onStartCapture = onStartCapture,
        )
        StoredCaptures(
            captures = state.captures,
            // Nothing to press mid-session, for the same reason the capture button
            // itself disappears there: deleting three files while the session is
            // about to write three more reads as a button that did not work.
            visible = !midSession,
            onDelete = onDeleteCaptures,
        )
    }
}

/**
 * What is on disk, and the offer to remove it.
 *
 * Hidden entirely when there is nothing: an always-present delete button with
 * nothing to delete is noise on a screen whose whole point is a short page.
 *
 * No confirmation step, on purpose. The line above the button says how many files
 * and how much they weigh, which is a better safeguard than a dialog that gets
 * dismissed reflexively — and the cost of a mistake is bounded, since arming a
 * capture already clears earlier sessions anyway. What the wording has to carry
 * instead is that these files are worth pulling off the phone first.
 */
@Composable
private fun StoredCaptures(captures: CapturesOnDisk, visible: Boolean, onDelete: () -> Unit) {
    if (captures.count == 0 || !visible) return

    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(
            R.string.captures_on_disk,
            captures.count,
            formatBytes(captures.bytes),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = EncreDouce,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.captures_personal_data),
        style = MaterialTheme.typography.bodySmall,
        color = EncreDouce,
    )
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onDelete,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.delete_captures),
            style = MaterialTheme.typography.labelSmall,
            color = Alerte,
        )
    }
}

/**
 * A repair tool, not a daily control: it recalibrates the rules when a watched
 * app drifts. Kept reachable, but given the least weight on the page.
 *
 * It used to be a bare button whose only feedback was a line in logcat, so the
 * three ways it can quietly do nothing — service off, never reaching a watched
 * app, window already over — all looked exactly like a working press.
 */
@Composable
private fun CaptureControl(
    status: CaptureStatus,
    phase: CapturePhase,
    now: Long,
    serviceEnabled: Boolean,
    onStartCapture: () -> Unit,
) {
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

