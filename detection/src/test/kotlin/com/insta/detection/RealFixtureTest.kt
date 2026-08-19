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
        // is present on published content and absent from a friend's story.
        //
        // This comment used to end "the subscribe button is NOT a discriminator:
        // it appears on both", and that was wrong. It came from the analysis that
        // truncated identifiers after the last "/". The fixtures committed
        // alongside it already disagreed — `snapchat_discover` carries
        // `context_chrome_header/chrome_subscribe_button` on screen and
        // `snapchat_story` does not — so this is not one fresh sample overturning
        // a measurement, it is the repository's own evidence finally being read.
        // A publisher story caught on the device on 2026-08-19 was the third tree
        // to agree, and it is the reason the signal was added: it carries neither
        // `spotlight_container` nor the action column, so nothing blocked it.
        val signals = ruleSet.apps
            .getValue("com.snapchat.android")
            .surfaces
            .getValue(Surface.DISCOVER)
            .signals

        assertEquals(3, signals.size)
        // The exact ids as measured on the device. Snapchat exposes these in its
        // own namespace, NOT as "com.snapchat.android:id/..." — writing the usual
        // package prefix here produces a rule that never fires, which is how this
        // rule shipped broken the first time.
        assertEquals(
            setOf(
                "context_vertical_actions/context_vertical_action_comment",
                "context_vertical_actions/context_vertical_action_favorite",
                "context_chrome_header/chrome_subscribe_button",
            ),
            signals.mapNotNull { it.value }.toSet(),
        )
        assertTrue("all must require an on-screen node", signals.all { it.requireOnScreen })
        assertTrue("none may require selection", signals.none { it.requireSelected })
    }
}

/**
 * The same, for YouTube and Snapchat.
 *
 * Until these existed, those rules were verified on the device and by nothing
 * else: an identifier renamed upstream would have been discovered by the user,
 * on a surface that silently stopped being blocked. Snapchat is the sharper
 * case — measured across these three fixtures, 241 of the 343 nodes that carry a
 * non-empty identifier are obfuscated, i.e. 70%, so its rules rest on a handful
 * of survivors.
 *
 * Every `contentDescription` in these trees is `[scrubbed]`, all of them rather
 * than a chosen subset: the raw captures carried a group name and a contact's
 * message in an in-app notification banner. Nothing here is load-bearing, since
 * all five rules are HIGH tier — resource identifiers.
 */
class MultiAppFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader().readText()

    private fun fixture(name: String): ScreenSnapshot =
        json.decodeFromString(resource("/fixtures/$name.json"))

    private val ruleSet = when (val result = RuleSetParser.parse(resource("/rules.json"))) {
        is ParseResult.Success -> result.ruleSet
        is ParseResult.Failure -> error("shipped rules are invalid: ${result.message}")
    }

    private val classifier = ScreenClassifier(ruleSet)

    @Test
    fun `the shorts player is detected at the high tier`() {
        val result = classifier.classify(fixture("youtube_shorts"))

        assertEquals(Surface.SHORTS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `the youtube home feed is not blocked`() {
        // The one that mattered on the device: the first capture attempt caught
        // this screen instead of Shorts, because Shorts was being blocked. It
        // earns its place as the negative case.
        assertEquals(Surface.OTHER, classifier.classify(fixture("youtube_home")).surface)
    }

    @Test
    fun `spotlight is detected at the high tier`() {
        val result = classifier.classify(fixture("snapchat_spotlight"))

        assertEquals(Surface.SPOTLIGHT, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `a discover video is detected at the high tier`() {
        val result = classifier.classify(fixture("snapchat_discover"))

        assertEquals(Surface.DISCOVER, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `a friend's story is not blocked`() {
        // The behaviour the user asked for by name, and the one most easily
        // broken by a careless widening of the Snapchat rules.
        assertEquals(Surface.OTHER, classifier.classify(fixture("snapchat_story")).surface)
    }

    @Test
    fun `the full-screen viewer alone decides nothing`() {
        // opera_viewer is on screen in Spotlight, in Discover and in a friend's
        // story alike. Any rule resting on it would block all three.
        val viewers = listOf("snapchat_spotlight", "snapchat_discover", "snapchat_story")
            .map { name -> fixture(name).nodes.any { it.viewId?.endsWith("opera_viewer") == true } }

        assertEquals(listOf(true, true, true), viewers)
    }

    @Test
    fun `a publisher story reached from the grid is blocked at the high tier`() {
        // The case the user hit: tapping a tile in the Snapchat grid mostly opens
        // a Spotlight, which `spotlight_container` catches — but a publisher's
        // story slips in among them now and then, and it carries neither that
        // container nor the vertical action column. Nothing fired, so nothing was
        // blocked. Measured on the device on 2026-08-19, on such a story caught
        // in the act.
        val result = classifier.classify(fixture("snapchat_publisher_story"))

        assertEquals(Surface.DISCOVER, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `neither older snapchat signal saw the publisher story`() {
        // Why the rule needed a third signal rather than a wider version of the
        // two it had. Stated over the tree so that a future edit which drops the
        // subscribe button cannot pass by widening one of the others instead.
        val ids = fixture("snapchat_publisher_story").nodes
            .filter { it.bounds.isOnScreen }
            .mapNotNull { it.viewId }
            .toSet()

        assertFalse(ids.any { it.endsWith("spotlight_container") })
        assertFalse(ids.any { it.contains("context_vertical_action") })
    }

    @Test
    fun `a spotlight video without its container is still blocked`() {
        // Second gap reported by the user on 2026-08-19, tree taken while the
        // video was on screen: an ordinary Spotlight — full-screen, right-hand
        // rail with heart, comments, repost, share, music track — that carries no
        // `spotlight_container` at all. The one signal SPOTLIGHT had could not see
        // it, so it played to the end.
        val result = classifier.classify(fixture("snapchat_spotlight_no_container"))

        assertEquals(Surface.SPOTLIGHT, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `that spotlight really is missing the container it is named after`() {
        // Guards the fixture itself. If a later capture replaced this tree with
        // one that does carry the container, the test above would keep passing
        // for the wrong reason and the `favorite` signal would look load-bearing
        // when nothing exercised it.
        val onScreen = fixture("snapchat_spotlight_no_container").nodes
            .filter { it.bounds.isOnScreen }
            .mapNotNull { it.viewId }
            .toSet()

        assertFalse(onScreen.any { it.endsWith("spotlight_container") })
        assertTrue(onScreen.contains("com.snapchat.android:id/favorite"))
    }

    @Test
    fun `the heart is on screen in spotlight and only a leftover elsewhere`() {
        // `favorite` is the heart on the Spotlight rail, measured at x 948..1066
        // in both Spotlight trees. It also appears in the Discover capture — but
        // with left 2028 and right 1080, a negative width, which is the leftover
        // shape `requireOnScreen` exists to reject. Without that flag this signal
        // would pull Discover into SPOTLIGHT, since SPOTLIGHT comes first in the
        // Surface enum and so wins a tie at the same tier.
        fun heart(name: String) = fixture(name).nodes
            .filter { it.viewId == "com.snapchat.android:id/favorite" }

        assertTrue(heart("snapchat_spotlight").any { it.bounds.isOnScreen })
        assertTrue(heart("snapchat_spotlight_no_container").any { it.bounds.isOnScreen })
        assertTrue(heart("snapchat_discover").isNotEmpty())
        assertFalse(heart("snapchat_discover").any { it.bounds.isOnScreen })
        assertTrue(heart("snapchat_story").isEmpty())
        assertTrue(heart("snapchat_publisher_story").isEmpty())
    }

    @Test
    fun `the subscribe button separates published content from a friend`() {
        // You subscribe to a publisher, never to a friend — which is why this id
        // can widen DISCOVER without touching the behaviour the user asked for by
        // name. Two independent measurements say it is there on published content
        // (the Discover capture of 2026-08-17, the publisher story of 2026-08-19)
        // and one says it is absent from a friend's story.
        //
        // An older analysis claimed it sat on both sides. That analysis truncated
        // identifiers after the last "/", which is documented in CLAUDE.md as
        // having produced a false conclusion here before; these three trees are
        // read whole.
        fun hasSubscribeButton(name: String) = fixture(name).nodes.any {
            it.viewId == "context_chrome_header/chrome_subscribe_button" && it.bounds.isOnScreen
        }

        assertTrue(hasSubscribeButton("snapchat_discover"))
        assertTrue(hasSubscribeButton("snapchat_publisher_story"))
        assertFalse(hasSubscribeButton("snapchat_story"))
        assertFalse(hasSubscribeButton("snapchat_spotlight"))
    }

    @Test
    fun `no snapchat signal names an id a friend's story shows`() {
        // The property behind "a friend's story stays watchable", stated over the
        // rules rather than over one outcome: any signal borrowed from the wrong
        // screen is caught here, not on the user's phone.
        val onFriendStory = fixture("snapchat_story").nodes
            .filter { it.bounds.isOnScreen }
            .mapNotNull { it.viewId }
            .toSet()

        val snapchat = ruleSet.apps.getValue("com.snapchat.android")
        for ((surface, rules) in snapchat.surfaces) {
            for (signal in rules.signals) {
                val value = signal.value ?: continue
                assertFalse(
                    "$surface rests on $value, which a friend's story also shows",
                    value in onFriendStory,
                )
            }
        }
    }

    @Test
    fun `the vertical action column is what separates discover from a story`() {
        fun hasColumn(name: String) = fixture(name).nodes.any {
            it.viewId?.contains("context_vertical_action") == true && it.bounds.isOnScreen
        }

        assertTrue(hasColumn("snapchat_discover"))
        assertFalse(hasColumn("snapchat_story"))
    }

    @Test
    fun `no shorts signal names an id the youtube home feed also shows`() {
        // The trap this test exists for, measured rather than guessed. The rules
        // used to carry `reel_progress_bar` as a second SHORTS signal; it matches
        // nothing in the Shorts capture, so it was removed. The obvious
        // replacement is `reel_time_bar`, the one `reel_*` id left in that
        // capture — and it is ALSO on the home feed, with full-screen bounds
        // {0, 0, 1080, 2424}, so `requireOnScreen` would not filter it out.
        // Adopting it would block the YouTube feed.
        //
        // Stated as a property rather than as that one id, so any future signal
        // borrowed from the wrong screen is caught the same way.
        val onHomeFeed = fixture("youtube_home").nodes
            .filter { it.bounds.isOnScreen }
            .mapNotNull { it.viewId }
            .toSet()

        for ((packageName, app) in ruleSet.apps) {
            val shorts = app.surfaces[Surface.SHORTS] ?: continue
            for (signal in shorts.signals) {
                val value = signal.value ?: continue
                // Compared on the bare id: the home capture is YouTube's, while a
                // signal may belong to one of the two other YouTube variants.
                val bare = value.substringAfter(":id/")
                assertFalse(
                    "$packageName/SHORTS rests on $value, which the YouTube home feed also shows",
                    onHomeFeed.any { it.substringAfter(":id/") == bare },
                )
            }
        }
    }

    @Test
    fun `no fixture carries a description that was not scrubbed`() {
        // Guards the privacy boundary itself. These trees came off a real phone,
        // and one of them held a contact's name and a message preview before
        // scrubbing; a future fixture added carelessly would be caught here.
        val descriptions = listOf(
            "youtube_shorts", "youtube_home",
            "snapchat_spotlight", "snapchat_discover", "snapchat_story",
            "snapchat_publisher_story", "snapchat_spotlight_no_container",
        ).flatMap { name -> fixture(name).nodes.mapNotNull { it.contentDescription } }

        assertEquals(setOf("[scrubbed]"), descriptions.toSet())
    }
}
