package com.insta.reelsoff.ui

import com.insta.detection.Surface

/** One block of switches in the screen: an app, and the surfaces it owns. */
data class SurfaceGroup(
    val packageName: String,
    val labelResId: Int,
    val surfaces: List<Surface>,
)

/**
 * The switch groups worth showing, given what is installed.
 *
 * YouTube's three installable variants share one heading and one switch: from
 * the user's side they are the same product, and offering "Shorts" three times
 * would be noise. The first installed variant names the group.
 */
fun surfaceGroups(installed: Set<String>): List<SurfaceGroup> {
    val catalogue = listOf(
        Triple(
            listOf("com.instagram.android"),
            com.insta.reelsoff.R.string.app_instagram,
            listOf(Surface.REELS, Surface.EXPLORE),
        ),
        Triple(
            listOf(
                "com.google.android.youtube",
                "com.google.android.apps.youtube.kids",
                "app.revanced.android.youtube",
            ),
            com.insta.reelsoff.R.string.app_youtube,
            listOf(Surface.SHORTS),
        ),
        Triple(
            listOf("com.snapchat.android"),
            com.insta.reelsoff.R.string.app_snapchat,
            listOf(Surface.SPOTLIGHT),
        ),
    )

    return catalogue.mapNotNull { (packages, labelResId, surfaces) ->
        val present = packages.firstOrNull { it in installed } ?: return@mapNotNull null
        SurfaceGroup(present, labelResId, surfaces)
    }
}
