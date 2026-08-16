package com.insta.reelsoff.service

import com.insta.detection.NodeSummary
import com.insta.detection.ScreenSnapshot

/**
 * Flattens a view tree into a snapshot, breadth-first.
 *
 * Both caps exist because this runs on the main thread on every qualifying
 * accessibility event: a pathological tree must cost a bounded amount of work,
 * not an unbounded one.
 */
class TreeWalker(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {

    fun walk(root: NodeLike?, packageName: String, capturedAtMillis: Long): ScreenSnapshot {
        val nodes = mutableListOf<NodeSummary>()
        if (root != null) {
            val queue = ArrayDeque<Pending>()
            queue.add(Pending(root, parentIndex = -1, depth = 0, indexInParent = 0))

            while (queue.isNotEmpty() && nodes.size < maxNodes) {
                val pending = queue.removeFirst()
                val index = nodes.size
                nodes.add(pending.toSummary(index))

                if (pending.depth + 1 < maxDepth) {
                    for (childPosition in 0 until pending.node.childCount) {
                        val child = pending.node.childAt(childPosition) ?: continue
                        queue.add(
                            Pending(
                                node = child,
                                parentIndex = index,
                                depth = pending.depth + 1,
                                indexInParent = childPosition,
                            ),
                        )
                    }
                }
            }
        }
        return ScreenSnapshot(packageName, capturedAtMillis, nodes)
    }

    private class Pending(
        val node: NodeLike,
        val parentIndex: Int,
        val depth: Int,
        val indexInParent: Int,
    ) {
        fun toSummary(index: Int) = NodeSummary(
            index = index,
            parentIndex = parentIndex,
            depth = depth,
            indexInParent = indexInParent,
            viewId = node.viewId,
            contentDescription = node.contentDescription,
            className = node.className,
            isSelected = node.isSelected,
            isClickable = node.isClickable,
            bounds = node.bounds,
        )
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 25
        const val DEFAULT_MAX_NODES = 800
    }
}
