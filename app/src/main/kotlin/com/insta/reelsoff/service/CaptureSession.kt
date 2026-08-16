package com.insta.reelsoff.service

/**
 * A timed window during which the service dumps view trees to disk.
 *
 * Time-based rather than button-based because the user cannot press a button
 * in this app while Instagram is in the foreground, and pulling down the
 * notification shade would change the active window — capturing the shade
 * instead of Instagram.
 */
class CaptureSession(
    private val clock: Clock,
    private val durationMillis: Long = 60_000,
    private val intervalMillis: Long = 3_000,
) {
    private var startedAtMillis = NEVER
    private var lastCaptureAtMillis = NEVER

    fun start() {
        startedAtMillis = clock.nowMillis()
        lastCaptureAtMillis = NEVER
    }

    fun isActive(): Boolean = clock.nowMillis() - startedAtMillis <= durationMillis

    fun shouldCapture(): Boolean {
        if (!isActive()) return false
        val now = clock.nowMillis()
        if (now - lastCaptureAtMillis < intervalMillis) return false
        lastCaptureAtMillis = now
        return true
    }
}
