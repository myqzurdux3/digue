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
) {
    companion object {
        val OTHER = Classification(Surface.OTHER, null)
    }
}
