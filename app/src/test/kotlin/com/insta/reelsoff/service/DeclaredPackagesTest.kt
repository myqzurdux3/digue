package com.insta.reelsoff.service

import com.insta.detection.AppRules
import com.insta.detection.RULES_VERSION
import com.insta.detection.RuleSet
import com.insta.detection.Signal
import com.insta.detection.SignalType
import com.insta.detection.Surface
import com.insta.detection.SurfaceRules
import com.insta.detection.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rules(vararg entries: Pair<String, Surface>) = RuleSet(
    version = RULES_VERSION,
    apps = entries.groupBy({ it.first }, { it.second }).mapValues { (pkg, surfaces) ->
        AppRules(
            surfaces.associateWith {
                SurfaceRules(listOf(Signal(Tier.HIGH, SignalType.VIEW_ID, value = "$pkg:id/x")))
            },
        )
    },
)

class DeclaredPackagesTest {

    private val all = rules(
        "com.instagram.android" to Surface.REELS,
        "com.instagram.android" to Surface.EXPLORE,
        "com.google.android.youtube" to Surface.SHORTS,
        "com.snapchat.android" to Surface.SPOTLIGHT,
    )

    @Test
    fun `declares only the packages whose surfaces are switched on`() {
        val declared = declaredPackages(all, setOf(Surface.REELS, Surface.SHORTS))

        assertEquals(setOf("com.instagram.android", "com.google.android.youtube"), declared)
    }

    @Test
    fun `a package is dropped once its last surface is switched off`() {
        // This is the whole point of the design: switching Snapchat off must make
        // the service incapable of receiving Snapchat's screens, not merely
        // uninterested in them.
        val declared = declaredPackages(all, setOf(Surface.REELS))

        assertEquals(setOf("com.instagram.android"), declared)
    }

    @Test
    fun `one enabled surface is enough to keep its package`() {
        val declared = declaredPackages(all, setOf(Surface.EXPLORE))

        assertEquals(setOf("com.instagram.android"), declared)
    }

    @Test
    fun `blocking nothing declares nothing`() {
        assertEquals(emptySet<String>(), declaredPackages(all, emptySet()))
    }

    @Test
    fun `an empty rule set declares nothing`() {
        assertEquals(
            emptySet<String>(),
            declaredPackages(RuleSet(version = 0, apps = emptyMap()), setOf(Surface.REELS)),
        )
    }

    @Test
    fun `an empty selection maps to exactly one unmatchable sentinel`() {
        val result = packageNamesFor(emptySet())

        assertEquals(1, result.size)
        assertEquals(NO_PACKAGE, result[0])
    }

    @Test
    fun `a non-empty selection maps to exactly those packages`() {
        val result = packageNamesFor(setOf("com.instagram.android", "com.snapchat.android"))

        assertEquals(setOf("com.instagram.android", "com.snapchat.android"), result.toSet())
        assertEquals(2, result.size)
    }

    @Test
    fun `the mapped result is never empty and never null`() {
        assertTrue(packageNamesFor(emptySet()).isNotEmpty())
        assertTrue(packageNamesFor(setOf("com.instagram.android")).isNotEmpty())
    }
}
