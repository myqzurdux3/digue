package com.insta.reelsoff.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.insta.detection.Bounds

/**
 * Thin adapter, deliberately logic-free: everything worth testing lives in
 * [TreeWalker], which knows nothing about Android.
 */
class AccessibilityNodeLike(private val node: AccessibilityNodeInfo) : NodeLike {

    override val viewId: String? get() = node.viewIdResourceName
    override val contentDescription: String? get() = node.contentDescription?.toString()
    override val className: String? get() = node.className?.toString()
    override val isSelected: Boolean get() = node.isSelected
    override val isClickable: Boolean get() = node.isClickable

    override val bounds: Bounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return Bounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val childCount: Int get() = node.childCount

    override fun childAt(index: Int): NodeLike? =
        node.getChild(index)?.let(::AccessibilityNodeLike)
}
