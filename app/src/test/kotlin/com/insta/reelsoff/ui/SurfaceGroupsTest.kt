package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceGroupsTest {

    @Test
    fun `offers only the apps that are installed`() {
        // Offering to block Snapchat to someone who does not have it is noise.
        val groups = surfaceGroups(installed = setOf("com.instagram.android"))

        assertEquals(listOf("com.instagram.android"), groups.map { it.packageName })
    }

    @Test
    fun `groups every youtube variant under one heading`() {
        val groups = surfaceGroups(
            installed = setOf("com.google.android.youtube", "app.revanced.android.youtube"),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(Surface.SHORTS), groups.single().surfaces)
    }

    @Test
    fun `instagram comes first`() {
        val groups = surfaceGroups(
            installed = setOf("com.snapchat.android", "com.instagram.android"),
        )

        assertEquals("com.instagram.android", groups.first().packageName)
    }

    @Test
    fun `nothing installed means nothing to show`() {
        assertTrue(surfaceGroups(installed = emptySet()).isEmpty())
    }

    @Test
    fun `labelForPackage does not require the app to be installed`() {
        // "Applications observees" reports what the service declares, which is not
        // filtered by installed-app detection — the label lookup must not be either.
        assertEquals(R.string.app_instagram, labelForPackage("com.instagram.android"))
    }

    @Test
    fun `labelForPackage recognises every youtube variant`() {
        assertEquals(R.string.app_youtube, labelForPackage("com.google.android.apps.youtube.kids"))
        assertEquals(R.string.app_youtube, labelForPackage("app.revanced.android.youtube"))
    }

    @Test
    fun `labelForPackage returns null for an unknown package`() {
        assertNull(labelForPackage("com.unknown.app"))
    }
}
