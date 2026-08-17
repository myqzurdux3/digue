package com.insta.detection

/**
 * The classifier's verdict. [tier] records which trust level answered, so the
 * app can tell the user when detection is running degraded — the only honest
 * signal that Instagram has changed underneath it.
 *
 * [tier] is null if and only if [surface] is [Surface.OTHER].
 */
data class Classification(
    val surface: Surface,
    val tier: Tier?,
    /**
     * A node to click instead of leaving the screen, copied from the matched
     * surface's rules. Explore uses it: blocking that tab also blocks Instagram's
     * only search, so the app lands the user in the search field rather than
     * bouncing them out. Null means the usual exit behaviour.
     */
    val clickViewId: String? = null,
) {
    companion object {
        val OTHER = Classification(Surface.OTHER, null)
    }
}
