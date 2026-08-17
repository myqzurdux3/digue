package com.insta.reelsoff.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta.detection.Surface
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.LockedSettings
import com.insta.reelsoff.service.PendingChange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        store.setSurfaceBlocked(Surface.EXPLORE, false)

        val settings = store.settings.first()

        assertTrue(Surface.REELS in settings.blockedSurfaces)
        assertFalse(Surface.EXPLORE in settings.blockedSurfaces)
        assertEquals(setOf(Surface.REELS), settings.blockedSurfaces)
    }

    @Test
    fun disablingBothYieldsAnEmptySet() = runBlocking {
        store.setSurfaceBlocked(Surface.REELS, false)
        store.setSurfaceBlocked(Surface.EXPLORE, false)

        assertTrue(store.settings.first().blockedSurfaces.isEmpty())
    }

    @Test
    fun newSurfacesAreOffByDefault() = runBlocking {
        // Turning them on would both start blocking and widen what the service is
        // allowed to see, neither of which the user asked for.
        val blocked = store.settings.first().blockedSurfaces

        assertFalse(Surface.SHORTS in blocked)
        assertFalse(Surface.SPOTLIGHT in blocked)
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
        // Surface.OTHER is not a user-facing switch (see TodayBreakdown), so the
        // four blockable surfaces are named explicitly rather than iterating
        // Surface.entries — this test's name promises "every surface", and a new
        // togglable surface added without updating this list should fail loudly.
        for (surface in listOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS, Surface.SPOTLIGHT)) {
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
        store.writeLegacyBlockExploreForTest(false)

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
    fun declaredPackagesIsEmptyUntilTheServiceWritesOne() = runBlocking {
        // Empty here must read as "not published yet", not as "declares nothing" —
        // the service has not run since the store was cleared.
        assertTrue(store.declaredPackages.first().isEmpty())
    }

    @Test
    fun declaredPackagesSurvivesTheRoundTrip() = runBlocking {
        store.setDeclaredPackages(setOf("com.instagram.android", "com.snapchat.android"))

        val declared = store.declaredPackages.first()

        assertEquals(setOf("com.instagram.android", "com.snapchat.android"), declared)
    }

    @Test
    fun allowanceSettingsRoundTrip() = runBlocking {
        assertEquals(AllowanceSettings(), store.allowanceSettings.first())

        val wanted = AllowanceSettings(
            enabled = true,
            quotaMillis = 420_000,
            windowStartMinutes = 19 * 60 + 30,
            windowEndMinutes = 20 * 60 + 15,
            cooldownMillis = 12 * 3_600_000,
        )
        store.setAllowanceSettings(wanted)

        assertEquals(wanted, store.allowanceSettings.first())
    }

    @Test
    fun aFreshInstallHasNoQuotaAndNoLock() = runBlocking {
        val settings = store.allowanceSettings.first()

        // Both defaults are load-bearing. Disabled is the *strictest* state: the
        // quota grants time and never removes any. And a zero cooldown is what
        // leaves the settings arrangeable — with a delay already in force,
        // switching the quota on would itself be a loosening and wait a day.
        assertFalse(settings.enabled)
        assertEquals(0L, settings.cooldownMillis)
    }

    @Test
    fun allowanceStateRoundTrip() = runBlocking {
        assertEquals(AllowanceState(), store.allowanceState.first())

        val wanted = AllowanceState(
            day = 20_683,
            consumedMillis = 90_000,
            passOpenedAtEpochMillis = 1_700_000_000_000,
        )
        store.setAllowanceState(wanted)

        assertEquals(wanted, store.allowanceState.first())
    }

    @Test
    fun pendingChangeRoundTripsAndClears() = runBlocking {
        assertNull(store.pendingChange.first())

        val wanted = PendingChange(
            proposed = LockedSettings(
                allowance = AllowanceSettings(enabled = true, quotaMillis = 600_000),
                blockedSurfaces = setOf(Surface.REELS, Surface.SHORTS),
            ),
            effectiveAtEpochMillis = 1_700_000_000_000,
            armedAtElapsedRealtime = 50_000,
            cooldownMillis = 24 * 3_600_000,
        )
        store.setPendingChange(wanted)
        assertEquals(wanted, store.pendingChange.first())

        store.setPendingChange(null)
        assertNull(store.pendingChange.first())
    }

    @Test
    fun anUnreadablePendingChangeReadsAsNoneRatherThanThrowing() = runBlocking {
        // Reading as "nothing pending" is the strict answer: a pending change
        // only ever loosens, so losing one costs nothing but safety.
        store.writeRawPendingChangeForTest("{ not json")

        assertNull(store.pendingChange.first())
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
