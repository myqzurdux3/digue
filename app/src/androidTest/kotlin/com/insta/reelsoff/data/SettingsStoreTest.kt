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
        store.clear()
    }

    @Test
    fun bothSurfacesAreBlockedByDefault() = runBlocking {
        val settings = store.settings.first()

        assertTrue(Surface.REELS in settings.blockedSurfaces)
        assertTrue(Surface.EXPLORE in settings.blockedSurfaces)
        assertEquals(setOf(Surface.REELS, Surface.EXPLORE), settings.blockedSurfaces)
    }

    @Test
    fun disablingExploreLeavesReelsBlocked() = runBlocking {
        store.setBlockExplore(false)

        val settings = store.settings.first()

        assertTrue(Surface.REELS in settings.blockedSurfaces)
        assertFalse(Surface.EXPLORE in settings.blockedSurfaces)
        assertEquals(setOf(Surface.REELS), settings.blockedSurfaces)
    }

    @Test
    fun disablingBothYieldsAnEmptySet() = runBlocking {
        store.setBlockReels(false)
        store.setBlockExplore(false)

        assertTrue(store.settings.first().blockedSurfaces.isEmpty())
    }

    @Test
    fun newSurfacesAreOffByDefault() = runBlocking {
        // Turning them on would both start blocking and widen what the service is
        // allowed to see, neither of which the user asked for.
        val blocked = store.settings.first().blockedSurfaces

        assertEquals(setOf(Surface.REELS, Surface.EXPLORE), blocked)
    }

    @Test
    fun aSurfaceCanBeSwitchedOnAndOff() = runBlocking {
        store.setSurfaceBlocked(Surface.SHORTS, true)
        assertTrue(Surface.SHORTS in store.settings.first().blockedSurfaces)

        store.setSurfaceBlocked(Surface.SHORTS, false)
        assertFalse(Surface.SHORTS in store.settings.first().blockedSurfaces)
    }

    @Test
    fun everySurfaceCanBeSwitchedOff() = runBlocking {
        for (surface in listOf(Surface.REELS, Surface.EXPLORE)) {
            store.setSurfaceBlocked(surface, false)
        }

        assertTrue(store.settings.first().blockedSurfaces.isEmpty())
    }

    @Test
    fun theOldBooleansAreCarriedOver() = runBlocking {
        // A user upgrading from the previous build has the two booleans and no
        // surface set. Ignoring them would silently re-enable something they had
        // turned off.
        store.clear()
        store.setBlockExplore(false)

        assertEquals(setOf(Surface.REELS), store.settings.first().blockedSurfaces)
    }

    @Test
    fun captureStatusIsEmptyUntilTheServiceWritesOne() = runBlocking {
        val status = store.captureStatus.first()

        // Zero is what makes the screen say IDLE, so this default is load-bearing:
        // any other value would show a phantom capture on first launch.
        assertEquals(0L, status.armedAtEpochMillis)
        assertEquals(0L, status.startedAtEpochMillis)
        assertEquals(0, status.count)
    }

    @Test
    fun captureStatusSurvivesTheRoundTrip() = runBlocking {
        store.setCaptureStatus(
            CaptureStatus(armedAtEpochMillis = 111L, startedAtEpochMillis = 222L, count = 7),
        )

        val status = store.captureStatus.first()

        assertEquals(111L, status.armedAtEpochMillis)
        assertEquals(222L, status.startedAtEpochMillis)
        assertEquals(7, status.count)
    }

    @Test
    fun armingAgainClearsTheEarlierWindow() = runBlocking {
        store.setCaptureStatus(
            CaptureStatus(armedAtEpochMillis = 111L, startedAtEpochMillis = 222L, count = 7),
        )

        store.setCaptureStatus(CaptureStatus(armedAtEpochMillis = 999L))

        // A re-arm must not leave the previous run's start and count behind, or the
        // screen would show a finished session while the new one is still waiting.
        val status = store.captureStatus.first()
        assertEquals(999L, status.armedAtEpochMillis)
        assertEquals(0L, status.startedAtEpochMillis)
        assertEquals(0, status.count)
    }
}
