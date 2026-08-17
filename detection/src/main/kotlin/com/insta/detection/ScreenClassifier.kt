package com.insta.detection

/**
 * Decides which screen a snapshot shows, for whichever app it came from.
 *
 * Signals are tried most-trusted first, across all surfaces, and the first
 * match wins. When an app renames its resource ids the HIGH tier stops
 * answering, the lower tiers keep working, and the reported tier tells the app
 * it is degraded.
 *
 * Within one tier, surfaces are tried in [Surface] declaration order — see the
 * note in [classify] for why that order has to come from the enum rather than
 * from the rules file.
 */
class ScreenClassifier(private val ruleSet: RuleSet) {

    fun classify(snapshot: ScreenSnapshot): Classification {
        // The snapshot names its own package, so the rules for another app can
        // never fire here — a second guard behind the system-level package
        // filter, which the service narrows at runtime.
        val appRules = ruleSet.apps[snapshot.packageName] ?: return Classification.OTHER
        // Both are derived at most once per classification, and only if some
        // signal actually asks for them. NONE rather than the default
        // synchronized mode: classify runs on the accessibility service's main
        // thread and in single-threaded tests, so the lock would be paid on
        // every read for nothing.
        val navBar by lazy(LazyThreadSafetyMode.NONE) { findNavBar(snapshot) }
        // Used to be rebuilt inside matches(), i.e. once per signal per tier —
        // up to a few dozen full copies of an 800-node list per walk, five walks
        // a second while scrolling.
        val onScreenNodes by lazy(LazyThreadSafetyMode.NONE) {
            snapshot.nodes.filter { it.bounds.isOnScreen }
        }

        for (tier in Tier.entries) {
            // Surface.entries order, deliberately, NOT the order the rules file
            // happens to list them in. A screen can satisfy two surfaces of the
            // same app at the same tier — Snapchat is the live case, where a
            // Spotlight video carrying a vertical action column would answer to
            // both SPOTLIGHT and DISCOVER — and whichever wins decides which
            // switch governs it. Resting that on the key order of a JSON object,
            // which a hand edit reorders without meaning to, would let a surface
            // silently stop being blocked.
            for (surface in Surface.entries) {
                val rules = appRules.surfaces[surface] ?: continue
                val matched = rules.signals.any {
                    it.tier == tier && matches(it, snapshot, onScreenNodes, navBar)
                }
                if (matched) return Classification(surface, tier, rules.clickViewId)
            }
        }
        return Classification.OTHER
    }

    private fun matches(
        signal: Signal,
        snapshot: ScreenSnapshot,
        onScreenNodes: List<NodeSummary>,
        navBar: List<NodeSummary>?,
    ): Boolean {
        val nodes = if (signal.requireOnScreen) onScreenNodes else snapshot.nodes

        if (signal.absentViewIds.isNotEmpty() &&
            nodes.any { it.viewId != null && it.viewId in signal.absentViewIds }
        ) {
            return false
        }

        return when (signal.type) {
            SignalType.VIEW_ID -> nodes.any { node ->
                node.viewId == signal.value && node.satisfies(signal)
            }

            SignalType.CONTENT_DESCRIPTION -> nodes.any { node ->
                node.contentDescription != null &&
                    signal.anyOf.any { it.equals(node.contentDescription, ignoreCase = true) } &&
                    node.satisfies(signal)
            }

            SignalType.NAV_BAR_INDEX -> {
                val index = signal.value?.toIntOrNull()
                val tab = if (index == null) null else navBar?.getOrNull(index)
                tab != null && tab.satisfies(signal) &&
                    (!signal.requireOnScreen || tab.bounds.isOnScreen)
            }
        }
    }

    private fun NodeSummary.satisfies(signal: Signal): Boolean =
        !signal.requireSelected || isSelected

    /**
     * Finds the bottom tab bar geometrically rather than by id, since geometry
     * is the one thing Instagram cannot rename: a row of at least four
     * clickable siblings, sitting lower on screen than any other such row.
     */
    private fun findNavBar(snapshot: ScreenSnapshot): List<NodeSummary>? =
        snapshot.nodes
            .filter { it.parentIndex >= 0 }
            .groupBy { it.parentIndex }
            .values
            .filter { siblings -> siblings.size >= MIN_TABS && siblings.all { it.isClickable } }
            .maxByOrNull { siblings -> siblings.minOf { it.bounds.top } }
            ?.sortedBy { it.indexInParent }

    private companion object {
        const val MIN_TABS = 4
    }
}
