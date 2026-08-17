package com.insta.detection

/**
 * Decides which Instagram screen a snapshot shows.
 *
 * Signals are tried most-trusted first, across all surfaces, and the first
 * match wins. When Instagram renames its resource ids the HIGH tier stops
 * answering, the lower tiers keep working, and the reported tier tells the app
 * it is degraded.
 */
class ScreenClassifier(private val ruleSet: RuleSet) {

    fun classify(snapshot: ScreenSnapshot): Classification {
        // The snapshot names its own package, so the rules for another app can
        // never fire here — a second guard behind the system-level package
        // filter, which the service narrows at runtime.
        val appRules = ruleSet.apps[snapshot.packageName] ?: return Classification.OTHER
        val navBar by lazy { findNavBar(snapshot) }

        for (tier in Tier.entries) {
            for ((surface, rules) in appRules.surfaces) {
                val matched = rules.signals
                    .filter { it.tier == tier }
                    .any { matches(it, snapshot, navBar) }
                if (matched) return Classification(surface, tier, rules.clickViewId)
            }
        }
        return Classification.OTHER
    }

    private fun matches(
        signal: Signal,
        snapshot: ScreenSnapshot,
        navBar: List<NodeSummary>?,
    ): Boolean {
        val nodes =
            if (signal.requireOnScreen) snapshot.nodes.filter { it.bounds.isOnScreen }
            else snapshot.nodes

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
