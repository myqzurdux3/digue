package com.insta.detection

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * True when the node occupies real space.
 *
 * Instagram does not tear down the previous screen, so its nodes linger in the
 * tree with collapsed or negative bounds. Measured on real captures: a leftover
 * reel pager reports width 0 on the feed and -2160 on the profile, while the
 * displayed one reports 1080. No threshold is needed to tell them apart.
 */
val Bounds.isOnScreen: Boolean
    get() = right > left && bottom > top

/**
 * One node of the observed view tree, flattened.
 *
 * Deliberately excludes node text: the classifier never needs it, so it is
 * never collected.
 */
@Serializable
data class NodeSummary(
    val index: Int,
    val parentIndex: Int,
    val depth: Int,
    val indexInParent: Int,
    val viewId: String?,
    val contentDescription: String?,
    val className: String?,
    val isSelected: Boolean,
    val isClickable: Boolean,
    val bounds: Bounds,
)

/** A whole view tree, flattened into a parent-indexed list. */
@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val capturedAtMillis: Long,
    val nodes: List<NodeSummary>,
)
