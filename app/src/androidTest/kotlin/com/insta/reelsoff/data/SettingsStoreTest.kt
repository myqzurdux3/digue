package com.insta.reelsoff.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta.detection.Surface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore(context)

    @Before
    fun reset() = runBlocking {
        store.setBlockReels(true)
        store.setBlockExplore(true)
    }

    @Test
    fun bothSurfacesAreBlockedByDefault() = runBlocking {
        val settings = store.settings.first()

        assertTrue(settings.blockReels)
        assertTrue(settings.blockExplore)
        assertEquals(setOf(Surface.REELS, Surface.EXPLORE), settings.blockedSurfaces)
    }

    @Test
    fun disablingExploreLeelsReelsBlocked() = runBlocking {
        store.setBlockExplore(false)

        val settings = store.settings.first()

        assertTrue(settings.blockReels)
        assertFalse(settings.blockExplore)
        assertEquals(setOf(Surface.REELS), settings.blockedSurfaces)
    }

    @Test
    fun disablingBothYieldsAnEmptySet() = runBlocking {
        store.setBlockReels(false)
        store.setBlockExplore(false)

        assertTrue(store.settings.first().blockedSurfaces.isEmpty())
    }
}
