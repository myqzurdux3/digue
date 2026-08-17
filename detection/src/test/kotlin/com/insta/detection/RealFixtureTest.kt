package com.insta.detection

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shipped rules against view trees actually captured from Instagram.
 * These fixtures are the project's ground truth; the synthetic tests in
 * ScreenClassifierTest cover the logic, these cover reality.
 */
class RealFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader().readText()

    private fun fixture(name: String): ScreenSnapshot =
        json.decodeFromString(resource("/fixtures/$name.json"))

    private val ruleSet: RuleSet =
        when (val result = RuleSetParser.parse(resource("/rules.json"))) {
            is ParseResult.Success -> result.ruleSet
            is ParseResult.Failure -> error("shipped rules are invalid: ${result.message}")
        }

    private val classifier = ScreenClassifier(ruleSet)

    @Test
    fun `shipped rules contain no leftover placeholders`() {
        assertTrue(
            "rules.json still contains a REMPLACER_ placeholder",
            !resource("/rules.json").contains("REMPLACER_"),
        )
    }

    @Test
    fun `feed is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("feed")).surface)
    }

    @Test
    fun `profile is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("profile")).surface)
    }

    @Test
    fun `direct messages are not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("direct")).surface)
    }

    @Test
    fun `reels tab is detected at the high tier`() {
        val result = classifier.classify(fixture("reels"))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `explore tab is detected at the high tier`() {
        val result = classifier.classify(fixture("explore"))

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    /** Proves the fallback works on real trees, not just synthetic ones. */
    @Test
    fun `reels is still detected when view ids are stripped`() {
        val stripped = fixture("reels").let { snapshot ->
            snapshot.copy(nodes = snapshot.nodes.map { it.copy(viewId = null) })
        }

        val result = classifier.classify(stripped)

        assertEquals(Surface.REELS, result.surface)
        assertNotEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `reels is still detected when view ids and labels are stripped`() {
        val stripped = fixture("reels").let { snapshot ->
            snapshot.copy(
                nodes = snapshot.nodes.map { it.copy(viewId = null, contentDescription = null) },
            )
        }

        val result = classifier.classify(stripped)

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.LOW, result.tier)
    }

    @Test
    fun `stripped feed is still not blocked`() {
        val stripped = fixture("feed").let { snapshot ->
            snapshot.copy(
                nodes = snapshot.nodes.map { it.copy(viewId = null, contentDescription = null) },
            )
        }

        assertEquals(Surface.OTHER, classifier.classify(stripped).surface)
    }

    @Test
    fun `a reel someone sent is not blocked`() {
        // The one that matters: if this ever returns REELS, the feature does the
        // opposite of what was asked.
        assertEquals(Surface.OTHER, classifier.classify(fixture("dm_reel")).surface)
    }

    @Test
    fun `the conversation itself is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("direct_thread")).surface)
    }

    @Test
    fun `the suggested reel that follows is blocked at the high tier`() {
        val result = classifier.classify(fixture("suggested_reel"))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `the shipped reel-viewer rule requires an on-screen node`() {
        // feed, profile and direct all carry a leftover clips_viewer_view_pager.
        // Drop requireOnScreen from rules.json and this fails — which is the
        // point: the failure is what stops the feed being blocked.
        val signal = ruleSet.apps.getValue("com.instagram.android").surfaces.getValue(Surface.REELS).signals
            .single { it.value?.endsWith("clips_viewer_view_pager") == true }

        assertTrue("the reel-viewer rule must require an on-screen node", signal.requireOnScreen)
        assertTrue("the reel-viewer rule must be guarded", signal.absentViewIds.isNotEmpty())
        assertFalse("the reel-viewer rule must not require selection", signal.requireSelected)
    }

    @Test
    fun `the shipped explore rule redirects to the search field`() {
        // Blocking Explore also blocks Instagram's only search, so the rule presses
        // the search field instead of bouncing the user out of the tab. Drop the
        // click target from rules.json and this fails.
        val result = classifier.classify(fixture("explore"))

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(
            "com.instagram.android:id/action_bar_search_edit_text",
            result.clickViewId,
        )
    }

    @Test
    fun `the shipped reels rule does not redirect`() {
        assertNull(classifier.classify(fixture("reels")).clickViewId)
    }

    @Test
    fun `a suggested reel is caught even when the conversation chrome lingers`() {
        // Redundancy against this app's recurring trap: Instagram does not tear
        // down the previous screen, so a reply bar left mounted over a suggested
        // reel would cancel the guard and silently exempt it. The "Suggested"
        // label is an independent signal that says the reel is algorithmic.
        val dmReel = fixture("dm_reel")
        val suggestedLabel = fixture("suggested_reel").nodes
            .single { it.viewId?.endsWith("suggested_title") == true }
        val lingering = dmReel.copy(nodes = dmReel.nodes + suggestedLabel)

        val result = classifier.classify(lingering)

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `the suggested-reel label is a shipped high-tier signal`() {
        val signal = ruleSet.apps.getValue("com.instagram.android").surfaces.getValue(Surface.REELS).signals
            .single { it.value?.endsWith("suggested_title") == true }

        assertEquals(Tier.HIGH, signal.tier)
        assertTrue("the label must be on screen to count", signal.requireOnScreen)
        assertFalse("the label is never selected", signal.requireSelected)
    }

    @Test
    fun `the shipped file is in the current format`() {
        assertEquals(RULES_VERSION, ruleSet.version)
    }

    @Test
    fun `youtube and snapchat rules are shipped`() {
        assertTrue(
            "every YouTube variant must carry SHORTS",
            listOf(
                "com.google.android.youtube",
                "com.google.android.apps.youtube.kids",
                "app.revanced.android.youtube",
            ).all { ruleSet.apps[it]?.surfaces?.containsKey(Surface.SHORTS) == true },
        )
        assertTrue(
            "Snapchat must carry SPOTLIGHT",
            ruleSet.apps["com.snapchat.android"]?.surfaces?.containsKey(Surface.SPOTLIGHT) == true,
        )
    }

    @Test
    fun `no shipped signal names an id belonging to another package`() {
        // Guards against copy-paste between app blocks, which would produce a rule
        // that can never match — this project's worst failure mode, because it is
        // indistinguishable from one that works.
        //
        // An id without a "<package>:id/" prefix is allowed: Snapchat exposes some
        // of its nodes in its own namespace, e.g.
        // "context_vertical_actions/context_vertical_action_comment". What is
        // forbidden is an id prefixed with a DIFFERENT package than the one whose
        // block it sits in.
        for ((packageName, app) in ruleSet.apps) {
            for ((surface, rules) in app.surfaces) {
                for (signal in rules.signals.filter { it.type == SignalType.VIEW_ID }) {
                    val value = signal.value ?: continue
                    if (!value.contains(":id/")) continue
                    assertTrue(
                        "$packageName/$surface names $value, which belongs to another package",
                        value.startsWith("$packageName:id/"),
                    )
                }
            }
        }
    }

    @Test
    fun `snapchat discover is recognised by the published-content action column`() {
        // Measured on the device: Snapchat plays a Discover video and a friend's
        // story in the same full-screen `opera_viewer`, so the viewer alone cannot
        // tell them apart. The vertical action column — comment, favourite, share —
        // is present on published content and absent from a friend's story. The
        // subscribe button is NOT a discriminator: it appears on both.
        val signals = ruleSet.apps
            .getValue("com.snapchat.android")
            .surfaces
            .getValue(Surface.DISCOVER)
            .signals

        assertEquals(2, signals.size)
        // The exact ids as measured on the device. Snapchat exposes these in its
        // own namespace, NOT as "com.snapchat.android:id/..." — writing the usual
        // package prefix here produces a rule that never fires, which is how this
        // rule shipped broken the first time.
        assertEquals(
            setOf(
                "context_vertical_actions/context_vertical_action_comment",
                "context_vertical_actions/context_vertical_action_favorite",
            ),
            signals.mapNotNull { it.value }.toSet(),
        )
        assertTrue("all must require an on-screen node", signals.all { it.requireOnScreen })
        assertTrue("none may require selection", signals.none { it.requireSelected })
    }
}
