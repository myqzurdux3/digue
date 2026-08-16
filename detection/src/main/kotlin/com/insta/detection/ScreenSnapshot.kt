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
