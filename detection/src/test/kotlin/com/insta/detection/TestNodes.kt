package com.insta.detection

fun node(
    index: Int,
    parentIndex: Int = -1,
    depth: Int = 0,
    indexInParent: Int = 0,
    viewId: String? = null,
    contentDescription: String? = null,
    className: String? = "android.widget.FrameLayout",
    isSelected: Boolean = false,
    isClickable: Boolean = false,
    bounds: Bounds = Bounds(0, 0, 1080, 200),
) = NodeSummary(
    index = index,
    parentIndex = parentIndex,
    depth = depth,
    indexInParent = indexInParent,
    viewId = viewId,
    contentDescription = contentDescription,
    className = className,
    isSelected = isSelected,
    isClickable = isClickable,
    bounds = bounds,
)

fun snapshot(nodes: List<NodeSummary>) =
    ScreenSnapshot("com.instagram.android", 0L, nodes)

/**
 * A five-tab bottom bar sitting at the bottom of a 2400px-tall screen,
 * plus some content above it so the bar is not the only container.
 *
 * Node 0 is the content container, node 1 its child, node 2 the bar itself,
 * nodes 3..7 the five tabs.
 */
fun screenWithNavBar(
    selectedTab: Int,
    tabViewIds: List<String?> = List(5) { null },
    tabDescriptions: List<String?> = List(5) { null },
): List<NodeSummary> {
    val content = listOf(
        node(index = 0, parentIndex = -1, depth = 0, bounds = Bounds(0, 0, 1080, 2400)),
        node(index = 1, parentIndex = 0, depth = 1, indexInParent = 0, bounds = Bounds(0, 0, 1080, 2200)),
        node(index = 2, parentIndex = 0, depth = 1, indexInParent = 1, bounds = Bounds(0, 2200, 1080, 2400)),
    )
    val tabs = (0 until 5).map { tab ->
        node(
            index = 3 + tab,
            parentIndex = 2,
            depth = 2,
            indexInParent = tab,
            viewId = tabViewIds[tab],
            contentDescription = tabDescriptions[tab],
            isSelected = tab == selectedTab,
            isClickable = true,
            bounds = Bounds(216 * tab, 2200, 216 * (tab + 1), 2400),
        )
    }
    return content + tabs
}

val TEST_RULES = RuleSet(
    version = RULES_VERSION,
    apps = mapOf(
        "com.instagram.android" to AppRules(
            mapOf(
                Surface.REELS to SurfaceRules(
                    listOf(
                        Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/clips_tab"),
                        Signal(Tier.MEDIUM, SignalType.CONTENT_DESCRIPTION, anyOf = listOf("Reels", "Réels")),
                        Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "2"),
                    ),
                ),
                Surface.EXPLORE to SurfaceRules(
                    listOf(
                        Signal(Tier.HIGH, SignalType.VIEW_ID, value = "com.instagram.android:id/search_tab"),
                        Signal(Tier.MEDIUM, SignalType.CONTENT_DESCRIPTION, anyOf = listOf("Search and explore", "Recherche et exploration")),
                        Signal(Tier.LOW, SignalType.NAV_BAR_INDEX, value = "1"),
                    ),
                ),
            ),
        ),
    ),
)
