package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.data.DailyCount

/**
 * Every surface with at least one block logged today, regardless of whether it is
 * still switched on.
 *
 * Filtering on "currently blocked" instead would let a surface the user just
 * switched off vanish from this list while its count stayed inside the total
 * shown above it — a total of 12 sitting over a breakdown that only adds up to 8.
 * Filtering on "has a count today" instead makes the breakdown sum to the total
 * by construction, no matter what is switched on right now.
 */
fun breakdownSurfaces(today: DailyCount?): List<Surface> =
    Surface.entries.filter { it != Surface.OTHER && (today?.countFor(it) ?: 0) > 0 }
