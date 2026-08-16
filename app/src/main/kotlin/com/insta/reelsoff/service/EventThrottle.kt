package com.insta.reelsoff.service

/**
 * Caps how often the tree gets walked. TYPE_WINDOW_CONTENT_CHANGED fires
 * continuously while scrolling; walking on every one of them would burn the
 * battery for no extra information.
 */
class EventThrottle(
    private val clock: Clock,
    private val minIntervalMillis: Long = 200,
) {
    private var lastProcessedAtMillis = NEVER

    fun shouldProcess(): Boolean {
        val now = clock.nowMillis()
        if (now - lastProcessedAtMillis < minIntervalMillis) return false
        lastProcessedAtMillis = now
        return true
    }
}
