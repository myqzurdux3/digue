package com.insta.reelsoff.service

import com.insta.detection.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNode(
    override val viewId: String? = null,
    override val contentDescription: String? = null,
    override val className: String? = "android.view.View",
    override val isSelected: Boolean = false,
    override val isClickable: Boolean = false,
    override val bounds: Bounds = Bounds(0, 0, 100, 100),
    private val children: List<FakeNode> = emptyList(),
) : NodeLike {
    override val childCount: Int get() = children.size
    override fun childAt(index: Int): NodeLike? = children.getOrNull(index)
}

/** A chain of [length] nodes, each the single child of the previous one. */
private fun chain(length: Int): FakeNode =
    (1..length).fold(FakeNode()) { acc, _ -> FakeNode(children = listOf(acc)) }

/** A node with [count] leaf children. */
private fun fanOut(count: Int): FakeNode =
    FakeNode(children = List(count) { FakeNode(viewId = "leaf-$it") })

class TreeWalkerTest {

    private val walker = TreeWalker()

    @Test
    fun `null root yields an empty snapshot`() {
        val snapshot = walker.walk(null, "com.instagram.android", 7L)

        assertEquals("com.instagram.android", snapshot.packageName)
        assertEquals(7L, snapshot.capturedAtMillis)
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun `copies every collected field`() {
        val root = FakeNode(
            viewId = "com.instagram.android:id/clips_tab",
            contentDescription = "Réels",
            className = "android.widget.ImageView",
            isSelected = true,
            isClickable = true,
            bounds = Bounds(1, 2, 3, 4),
        )

        val node = walker.walk(root, "com.instagram.android", 0L).nodes.single()

        assertEquals("com.instagram.android:id/clips_tab", node.viewId)
        assertEquals("Réels", node.contentDescription)
        assertEquals("android.widget.ImageView", node.className)
        assertTrue(node.isSelected)
        assertTrue(node.isClickable)
        assertEquals(Bounds(1, 2, 3, 4), node.bounds)
    }

    @Test
    fun `records parent index depth and position`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "a"),
                FakeNode(viewId = "b", children = listOf(FakeNode(viewId = "b1"))),
            ),
        )

        val nodes = walker.walk(root, "com.instagram.android", 0L).nodes
        val b1 = nodes.single { it.viewId == "b1" }
        val b = nodes.single { it.viewId == "b" }

        assertEquals(-1, nodes[0].parentIndex)
        assertEquals(0, nodes[0].depth)
        assertEquals(b.index, b1.parentIndex)
        assertEquals(2, b1.depth)
        assertEquals(1, b.indexInParent)
        assertEquals(0, b1.indexInParent)
    }

    @Test
    fun `index equals position in the flat list`() {
        val nodes = walker.walk(fanOut(5), "com.instagram.android", 0L).nodes

        nodes.forEachIndexed { position, node -> assertEquals(position, node.index) }
    }

    @Test
    fun `stops descending past the depth cap`() {
        val nodes = TreeWalker(maxDepth = 3).walk(chain(50), "com.instagram.android", 0L).nodes

        assertEquals(3, nodes.size)
        assertEquals(2, nodes.maxOf { it.depth })
    }

    @Test
    fun `stops collecting past the node cap`() {
        val nodes = TreeWalker(maxNodes = 10).walk(fanOut(500), "com.instagram.android", 0L).nodes

        assertEquals(10, nodes.size)
    }

    @Test
    fun `skips null children without losing the siblings after them`() {
        val root = object : NodeLike {
            override val viewId: String? = "root"
            override val contentDescription: String? = null
            override val className: String? = "android.view.View"
            override val isSelected: Boolean = false
            override val isClickable: Boolean = false
            override val bounds: Bounds = Bounds(0, 0, 0, 0)
            override val childCount: Int = 3
            override fun childAt(index: Int): NodeLike? =
                if (index == 1) null else FakeNode(viewId = "child-$index")
        }

        val nodes = walker.walk(root, "com.instagram.android", 0L).nodes

        assertEquals(listOf("root", "child-0", "child-2"), nodes.map { it.viewId })
        val child0 = nodes.single { it.viewId == "child-0" }
        val child2 = nodes.single { it.viewId == "child-2" }
        assertEquals(0, child0.indexInParent)
        assertEquals(2, child2.indexInParent)
    }
}
