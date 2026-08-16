package com.insta.reelsoff.service

import com.insta.detection.Bounds

/**
 * The slice of AccessibilityNodeInfo this app reads. Exists so the walking
 * logic can be tested on the JVM with fakes, and so the set of collected
 * fields is visible in one place — note the absence of `text`.
 */
interface NodeLike {
    val viewId: String?
    val contentDescription: String?
    val className: String?
    val isSelected: Boolean
    val isClickable: Boolean
    val bounds: Bounds
    val childCount: Int
    fun childAt(index: Int): NodeLike?
}
