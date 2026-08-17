package com.insta.reelsoff.ui

import com.insta.detection.Surface

/** One block of switches in the screen: an app, and the surfaces it owns. */
data class SurfaceGroup(
    val packageName: String,
    val labelResId: Int,
    val surfaces: List<Surface>,
)

/**
 * Every app this screen knows a label for, and the package names it ships under.
 *
 * Shared between [surfaceGroups] (gated by what is installed, for the switches)
 * and [labelForPackage] (ungated, for reporting what the service actually
 * declares to Android) — one table so the two views of "which apps" can never
 * name an app differently.
 */
private val CATALOGUE = listOf(
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

/**
 * The switch groups worth showing, given what is installed.
 *
 * YouTube's three installable variants share one heading and one switch: from
 * the user's side they are the same product, and offering "Shorts" three times
 * would be noise. The first installed variant names the group.
 */
fun surfaceGroups(installed: Set<String>): List<SurfaceGroup> =
    CATALOGUE.mapNotNull { (packages, labelResId, surfaces) ->
        val present = packages.firstOrNull { it in installed } ?: return@mapNotNull null
        SurfaceGroup(present, labelResId, surfaces)
    }

/**
 * The display label for [packageName], independent of whether it is currently
 * installed. Used for reporting what the service is actually allowed to observe
 * (see `DeclaredPackages.kt`) — that must stay accurate even when installed-app
 * detection is empty or stale, unlike the switches above, which rightly hide an
 * app nobody has.
 */
fun labelForPackage(packageName: String): Int? =
    CATALOGUE.firstOrNull { (packages, _, _) -> packageName in packages }?.second
