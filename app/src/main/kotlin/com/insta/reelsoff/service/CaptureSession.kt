package com.insta.reelsoff.service

/**
 * A timed window during which the service dumps view trees to disk.
 *
 * Time-based rather than button-based because the user cannot press a button
 * in this app while the watched app is in the foreground, and pulling down the
 * notification shade would change the active window — capturing the shade
 * instead of the app being captured.
 *
 * Arming and starting are deliberately separate. The button only *arms* the
 * session; the window opens on the first event from a watched app. Starting the
 * clock at the press charged the walk from this app to that one against the 60
 * seconds, which on a real device ate a third of the capture before anything was
 * recorded. A stale arming expires rather than firing hours later, when the user
 * has long forgotten they pressed it.
 */
class CaptureSession(
    private val clock: Clock,
    private val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    private val intervalMillis: Long = 3_000,
    private val armTimeoutMillis: Long = ARM_TIMEOUT_MILLIS,
) {
    /** Private: nothing outside reads it. The UI is told through `CaptureStatus`. */
    private var armedAtMillis: Long = NEVER

    /** [NEVER] until the first event from a watched app opens the window. */
    var startedAtMillis: Long = NEVER
        private set

    /** Snapshots actually written, not calls made — the user is told file counts. */
    var capturedCount: Int = 0
        private set

    private var lastCaptureAtMillis = NEVER

    fun arm() {
        armedAtMillis = clock.nowMillis()
        startedAtMillis = NEVER
        lastCaptureAtMillis = NEVER
        capturedCount = 0
    }

    fun shouldCapture(): Boolean {
        if (armedAtMillis == NEVER) return false
        val now = clock.nowMillis()

        if (startedAtMillis == NEVER) {
            if (now - armedAtMillis > armTimeoutMillis) return false
            startedAtMillis = now
        } else if (now - startedAtMillis > durationMillis) {
            return false
        }

        if (now - lastCaptureAtMillis < intervalMillis) return false
        lastCaptureAtMillis = now
        capturedCount++
        return true
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 60_000L

        /** How long an armed session waits for a watched app before giving up. */
        const val ARM_TIMEOUT_MILLIS = 5 * 60_000L
    }
}
