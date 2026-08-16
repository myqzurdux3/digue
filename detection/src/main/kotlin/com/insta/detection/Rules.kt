package com.insta.detection

import kotlinx.serialization.Serializable

/** How much a signal can be trusted when Instagram changes its view tree. */
enum class Tier {
    HIGH,
    MEDIUM,
    LOW,
}

enum class SignalType {
    /** Exact match on a resource id. Breaks when Instagram renames it. */
    VIEW_ID,

    /** Match on the accessibility label. Depends on the device language. */
    CONTENT_DESCRIPTION,

    /** Position within the bottom navigation bar, located geometrically. */
    NAV_BAR_INDEX,
}

@Serializable
data class Signal(
    val tier: Tier,
    val type: SignalType,
    val value: String? = null,
    val anyOf: List<String> = emptyList(),
    val requireSelected: Boolean = true,
)

@Serializable
data class SurfaceRules(val signals: List<Signal>)

data class RuleSet(
    val version: Int,
    val surfaces: Map<Surface, SurfaceRules>,
)
