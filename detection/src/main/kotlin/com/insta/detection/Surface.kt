package com.insta.detection

/**
 * A screen this app cares about. Surfaces stay app-specific rather than being
 * abstracted into one "short video feed": each one carries its own switch and
 * its own counter, which is what the screen already knows how to show.
 *
 * [OTHER] means "nothing to do here" and never carries rules.
 */
enum class Surface {
    REELS,
    EXPLORE,
    SHORTS,
    SPOTLIGHT,
    DISCOVER,
    OTHER,
}
