package com.insta.detection

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes a snapshot written by the capture tool`() {
        val raw = """
            {
              "packageName": "com.instagram.android",
              "capturedAtMillis": 1723800000000,
              "nodes": [
                {
                  "index": 0,
                  "parentIndex": -1,
                  "depth": 0,
                  "indexInParent": 0,
                  "viewId": "com.instagram.android:id/root",
                  "contentDescription": null,
                  "className": "android.widget.FrameLayout",
                  "isSelected": false,
                  "isClickable": false,
                  "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 2400 }
                }
              ]
            }
        """.trimIndent()

        val snapshot = json.decodeFromString<ScreenSnapshot>(raw)

        assertEquals("com.instagram.android", snapshot.packageName)
        assertEquals(1, snapshot.nodes.size)
        assertEquals("com.instagram.android:id/root", snapshot.nodes[0].viewId)
        assertEquals(-1, snapshot.nodes[0].parentIndex)
        assertEquals(2400, snapshot.nodes[0].bounds.bottom)
    }

    @Test
    fun `round trips through json`() {
        val original = ScreenSnapshot(
            packageName = "com.instagram.android",
            capturedAtMillis = 42L,
            nodes = listOf(
                NodeSummary(
                    index = 0,
                    parentIndex = -1,
                    depth = 0,
                    indexInParent = 0,
                    viewId = null,
                    contentDescription = "Réels",
                    className = "android.widget.ImageView",
                    isSelected = true,
                    isClickable = true,
                    bounds = Bounds(0, 2200, 216, 2400),
                ),
            ),
        )

        val decoded = json.decodeFromString<ScreenSnapshot>(json.encodeToString(original))

        assertEquals(original, decoded)
    }
}
