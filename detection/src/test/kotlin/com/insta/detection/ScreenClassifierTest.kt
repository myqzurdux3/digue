package com.insta.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenClassifierTest {

    private val classifier = ScreenClassifier(TEST_RULES)

    private val allTabViewIds = listOf(
        "com.instagram.android:id/feed_tab",
        "com.instagram.android:id/search_tab",
        "com.instagram.android:id/clips_tab",
        "com.instagram.android:id/creation_tab",
        "com.instagram.android:id/profile_tab",
    )

    private val allTabDescriptions =
        listOf("Home", "Search and explore", "Reels", "Create", "Profile")

    /**
     * The critical test. The Reels tab button exists on the feed too; only its
     * selected state means anything. Getting this wrong bounces the user out of
     * their own feed.
     */
    @Test
    fun `feed is OTHER even though the reels tab is present`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 0, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.OTHER, result.surface)
        assertNull(result.tier)
    }

    @Test
    fun `selected reels tab is REELS at the high tier`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `selected search tab is EXPLORE at the high tier`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 1, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `falls back to the medium tier when view ids are gone`() {
        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.MEDIUM, result.tier)
    }

    @Test
    fun `falls back to the low tier when view ids and labels are gone`() {
        val result = classifier.classify(snapshot(screenWithNavBar(selectedTab = 2)))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.LOW, result.tier)
    }

    @Test
    fun `content description match is case insensitive and accepts the french label`() {
        val french = listOf("Accueil", "Recherche et exploration", "Réels", "Créer", "Profil")

        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabDescriptions = french)),
        )

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.MEDIUM, result.tier)
    }

    /** A screen with no bottom bar at all — a full-screen story viewer, say. */
    @Test
    fun `screen without a nav bar is OTHER`() {
        val result = classifier.classify(
            snapshot(
                listOf(
                    node(index = 0, bounds = Bounds(0, 0, 1080, 2400)),
                    node(index = 1, parentIndex = 0, depth = 1, bounds = Bounds(0, 0, 1080, 2400)),
                ),
            ),
        )

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `empty snapshot is OTHER`() {
        assertEquals(Surface.OTHER, classifier.classify(snapshot(emptyList())).surface)
    }

    /**
     * A high-tier match on one surface must beat a low-tier match on another,
     * so tiers are evaluated across all surfaces before moving down.
     */
    @Test
    fun `high tier on explore wins over low tier on reels`() {
        val rules = RuleSet(
            version = RULES_VERSION,
            apps = mapOf(
                "com.instagram.android" to AppRules(
                    mapOf(
                        Surface.REELS to SurfaceRules(listOf(Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "1"))),
                        Surface.EXPLORE to SurfaceRules(
                            listOf(Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/search_tab")),
                        ),
                    ),
                ),
            ),
        )

        val result = ScreenClassifier(rules).classify(
            snapshot(screenWithNavBar(selectedTab = 1, tabViewIds = allTabViewIds)),
        )

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    /**
     * Two surfaces of one app, same tier, both matching. The winner decides which
     * switch governs the screen, so it must not depend on the order the rules file
     * happens to list them in — a hand edit reorders a JSON object without meaning
     * anything by it, and the loser silently stops being blocked.
     *
     * Written with the map built in the *wrong* order on purpose: SPOTLIGHT is
     * declared second here and must still win, being first in [Surface].
     */
    @Test
    fun `within a tier the enum order decides, not the rules file order`() {
        fun rulesListing(vararg surfaces: Pair<Surface, SurfaceRules>) = RuleSet(
            version = RULES_VERSION,
            apps = mapOf("com.snapchat.android" to AppRules(linkedMapOf(*surfaces))),
        )

        val discover = Surface.DISCOVER to SurfaceRules(
            listOf(
                Signal(
                    Tier.HIGH,
                    SignalType.VIEW_ID,
                    value = "context_vertical_actions/context_vertical_action_comment",
                    requireSelected = false,
                ),
            ),
        )
        val spotlight = Surface.SPOTLIGHT to SurfaceRules(
            listOf(
                Signal(
                    Tier.HIGH,
                    SignalType.VIEW_ID,
                    value = "com.snapchat.android:id/spotlight_container",
                    requireSelected = false,
                ),
            ),
        )

        val bothPresent = snapshot(
            listOf(
                node(index = 0),
                node(
                    index = 1,
                    parentIndex = 0,
                    depth = 1,
                    viewId = "com.snapchat.android:id/spotlight_container",
                ),
                node(
                    index = 2,
                    parentIndex = 0,
                    depth = 1,
                    viewId = "context_vertical_actions/context_vertical_action_comment",
                ),
            ),
            packageName = "com.snapchat.android",
        )

        assertEquals(
            Surface.SPOTLIGHT,
            ScreenClassifier(rulesListing(discover, spotlight)).classify(bothPresent).surface,
        )
        assertEquals(
            Surface.SPOTLIGHT,
            ScreenClassifier(rulesListing(spotlight, discover)).classify(bothPresent).surface,
        )
    }

    @Test
    fun `requireSelected false matches on mere presence`() {
        val rules = RuleSet(
            version = RULES_VERSION,
            apps = mapOf(
                "com.instagram.android" to AppRules(
                    mapOf(
                        Surface.REELS to SurfaceRules(
                            listOf(
                                Signal(
                                    Tier.HIGH,
                                    SignalType.VIEW_ID,
                                    value = "com.instagram.android:id/clips_viewer_video_container",
                                    requireSelected = false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = ScreenClassifier(rules).classify(
            snapshot(
                listOf(
                    node(index = 0),
                    node(
                        index = 1,
                        parentIndex = 0,
                        depth = 1,
                        viewId = "com.instagram.android:id/clips_viewer_video_container",
                    ),
                ),
            ),
        )

        assertEquals(Surface.REELS, result.surface)
    }

    /**
     * The degraded-mode guarantee: when rule loading fails entirely (see
     * RuleSetLoader), the app falls back to an empty rule set rather than
     * crashing the service. That fallback must be genuinely non-blocking —
     * a classifier with no rules must never report anything but OTHER.
     */
    @Test
    fun `empty rule set never classifies anything but OTHER`() {
        val classifier = ScreenClassifier(RuleSet(version = 0, apps = emptyMap()))

        val result = classifier.classify(
            snapshot(screenWithNavBar(selectedTab = 2, tabViewIds = allTabViewIds, tabDescriptions = allTabDescriptions)),
        )

        assertEquals(Surface.OTHER, result.surface)
        assertNull(result.tier)
    }

    /** The bar is picked geometrically, so a higher row of buttons must not win. */
    @Test
    fun `nav bar detection picks the lowest row of clickable siblings`() {
        val decoy = (0 until 5).map { tab ->
            node(
                index = 8 + tab,
                parentIndex = 1,
                depth = 2,
                indexInParent = tab,
                isSelected = tab == 2,
                isClickable = true,
                bounds = Bounds(216 * tab, 300, 216 * (tab + 1), 500),
            )
        }

        val result = classifier.classify(snapshot(screenWithNavBar(selectedTab = 0) + decoy))

        assertEquals(Surface.OTHER, result.surface)
    }

    private fun pagerRules(requireOnScreen: Boolean) = RuleSet(
        version = RULES_VERSION,
        apps = mapOf(
            "com.instagram.android" to AppRules(
                mapOf(
                    Surface.REELS to SurfaceRules(
                        listOf(
                            Signal(
                                tier = Tier.HIGH,
                                type = SignalType.VIEW_ID,
                                value = "pager",
                                requireSelected = false,
                                requireOnScreen = requireOnScreen,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a zero-width node does not satisfy an on-screen signal`() {
        // Measured on the real feed: Instagram leaves the previous screen's
        // pager in the tree at left=1080, right=1080.
        val leftover = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(1080, 152, 1080, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(leftover)

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `a negative-width node does not satisfy an on-screen signal`() {
        // Measured on the real profile screen: right=-2160.
        val leftover = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(0, 152, -2160, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(leftover)

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `a zero-height node does not satisfy an on-screen signal`() {
        val flat = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(0, 152, 1080, 152))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(flat)

        assertEquals(Surface.OTHER, result.surface)
    }

    @Test
    fun `a full-size node satisfies an on-screen signal`() {
        val visible = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(0, 152, 1080, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = true)).classify(visible)

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `without the flag a degenerate node still matches`() {
        // Every shipped rule leaves requireOnScreen at its default, so this is
        // the guarantee that none of them changes meaning.
        val leftover = snapshot(
            listOf(node(index = 0, viewId = "pager", bounds = Bounds(1080, 152, 1080, 2235))),
        )

        val result = ScreenClassifier(pagerRules(requireOnScreen = false)).classify(leftover)

        assertEquals(Surface.REELS, result.surface)
    }

    private fun guardedRules(requireOnScreen: Boolean = true) = RuleSet(
        version = RULES_VERSION,
        apps = mapOf(
            "com.instagram.android" to AppRules(
                mapOf(
                    Surface.REELS to SurfaceRules(
                        listOf(
                            Signal(
                                tier = Tier.HIGH,
                                type = SignalType.VIEW_ID,
                                value = "pager",
                                requireSelected = false,
                                requireOnScreen = requireOnScreen,
                                absentViewIds = listOf("reply_bar", "sender_name"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val visiblePager =
        node(index = 0, viewId = "pager", bounds = Bounds(0, 152, 1080, 2235))

    @Test
    fun `a guarded signal matches when no guard is present`() {
        val result = ScreenClassifier(guardedRules()).classify(snapshot(listOf(visiblePager)))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `a single guard suppresses the signal`() {
        // The reel a contact sent: the reply bar is what makes it exempt.
        val withReplyBar = snapshot(
            listOf(
                visiblePager,
                node(index = 1, viewId = "reply_bar", bounds = Bounds(0, 2000, 1080, 2200)),
            ),
        )

        assertEquals(Surface.OTHER, ScreenClassifier(guardedRules()).classify(withReplyBar).surface)
    }

    @Test
    fun `any one of several guards is enough`() {
        val withSender = snapshot(
            listOf(
                visiblePager,
                node(index = 1, viewId = "sender_name", bounds = Bounds(0, 300, 600, 360)),
            ),
        )

        assertEquals(Surface.OTHER, ScreenClassifier(guardedRules()).classify(withSender).surface)
    }

    @Test
    fun `a degenerate guard does not suppress an on-screen signal`() {
        // Symmetry with Task 1: if a leftover reply bar counted as present, the
        // trap would fire in reverse and silently cancel a legitimate block.
        val leftoverGuard = snapshot(
            listOf(
                visiblePager,
                node(index = 1, viewId = "reply_bar", bounds = Bounds(1080, 2000, 1080, 2200)),
            ),
        )

        assertEquals(Surface.REELS, ScreenClassifier(guardedRules()).classify(leftoverGuard).surface)
    }

    @Test
    fun `an empty guard list changes nothing`() {
        val rules = RuleSet(
            version = RULES_VERSION,
            apps = mapOf(
                "com.instagram.android" to AppRules(
                    mapOf(
                        Surface.REELS to SurfaceRules(
                            listOf(
                                Signal(
                                    tier = Tier.HIGH,
                                    type = SignalType.VIEW_ID,
                                    value = "pager",
                                    requireSelected = false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(Surface.REELS, ScreenClassifier(rules).classify(snapshot(listOf(visiblePager))).surface)
    }

    @Test
    fun `one app's rules never fire on another app`() {
        // The system-level package filter is the first guard; this is the second.
        // A YouTube id that happened to collide with an Instagram one must not
        // block Instagram, and vice versa.
        val rules = RuleSet(
            version = 2,
            apps = mapOf(
                "com.google.android.youtube" to AppRules(
                    mapOf(
                        Surface.SHORTS to SurfaceRules(
                            listOf(
                                Signal(
                                    tier = Tier.HIGH,
                                    type = SignalType.VIEW_ID,
                                    value = "shared_id",
                                    requireSelected = false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val instagramScreen = ScreenSnapshot(
            packageName = "com.instagram.android",
            capturedAtMillis = 0L,
            nodes = listOf(node(index = 0, viewId = "shared_id")),
        )

        assertEquals(Surface.OTHER, ScreenClassifier(rules).classify(instagramScreen).surface)
    }

    @Test
    fun `a package with no rules is never blocked`() {
        val rules = RuleSet(version = 2, apps = emptyMap())
        val screen = ScreenSnapshot("com.whatever.app", 0L, listOf(node(index = 0, viewId = "x")))

        assertEquals(Surface.OTHER, ScreenClassifier(rules).classify(screen).surface)
    }
}
