package com.insta.reelsoff.ui

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta.detection.Surface
import com.insta.reelsoff.data.SettingsStore
import com.insta.reelsoff.service.AllowanceSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one door every settings write goes through.
 *
 * `HomeViewModel` had no test at all, and the pure functions it calls being
 * covered is not the same thing: the defect that actually shipped was the order
 * it called them in. So these drive the real ViewModel against the real
 * DataStore and read the result back out of the store, rather than asserting on
 * anything the ViewModel says about itself.
 *
 * Instrumented rather than JVM because the ViewModel takes an `Application`,
 * builds its own `SettingsStore` and `AppDatabase` from it, and reads
 * `SystemClock.elapsedRealtime`. Faking all of that would be testing the fake.
 */
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {

    private val application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val store = SettingsStore(application)

    private lateinit var viewModel: HomeViewModel

    /** An hour of delay in force, so every loosening below has something to wait for. */
    private val locked = AllowanceSettings(
        enabled = true,
        quotaMillis = 15 * 60_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 22 * 60,
        cooldownMillis = 60 * 60_000,
    )

    @Before
    fun reset() {
        runBlocking {
            store.clear()
            store.setAllowanceSettings(locked)
            store.setSurfaceBlocked(Surface.REELS, true)
            store.setSurfaceBlocked(Surface.EXPLORE, true)
            store.setSurfaceBlocked(Surface.SHORTS, false)
        }
        // viewModelScope dispatches on the main thread; building it anywhere else
        // leaves every launched write pending for good.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = HomeViewModel(application)
        }
    }

    /**
     * The writes are launched into `viewModelScope`, so nothing is synchronous
     * from here. Polls the store rather than sleeping a fixed amount: a fixed
     * sleep either flakes or wastes the whole suite's time.
     */
    private fun awaitStore(what: String, predicate: suspend () -> Boolean) = runBlocking {
        repeat(100) {
            if (predicate()) return@runBlocking
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun aLooseningIsHeldRatherThanApplied() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.proposeAllowanceSettings(locked.copy(quotaMillis = 30 * 60_000))
        }

        awaitStore("the loosening to be armed") { store.pendingChange.first() != null }
        runBlocking {
            val pending = store.pendingChange.first()
            assertNotNull(pending)
            assertEquals(30 * 60_000L, pending!!.proposed.allowance.quotaMillis)
            // The delay charged is the one in force, never the proposed one.
            assertEquals(60 * 60_000L, pending.cooldownMillis)
            // And nothing moved in the settings themselves.
            assertEquals(15 * 60_000L, store.allowanceSettings.first().quotaMillis)
        }
    }

    @Test
    fun aTighteningAppliesAtOnceAndClearsWhatWasHeld() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.proposeAllowanceSettings(locked.copy(quotaMillis = 30 * 60_000))
        }
        awaitStore("the loosening to be armed") { store.pendingChange.first() != null }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.proposeAllowanceSettings(locked.copy(quotaMillis = 5 * 60_000))
        }

        awaitStore("the tightening to land") {
            store.allowanceSettings.first().quotaMillis == 5 * 60_000L
        }
        runBlocking {
            // Left armed, the loosening would have undone this tightening an hour
            // later, with nothing on screen to say so.
            assertNull(store.pendingChange.first())
            assertEquals(5 * 60_000L, store.allowanceSettings.first().quotaMillis)
        }
    }

    @Test
    fun theLockAlsoGuardsTheSurfaceSwitches() {
        // The bypass this exists to close: a lock that held only the quota would
        // be worth nothing, because switching REELS off would unblock the feed
        // outright and immediately.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.setSurfaceBlocked(Surface.REELS, false)
        }

        awaitStore("the unblocking to be armed") { store.pendingChange.first() != null }
        runBlocking {
            val pending = store.pendingChange.first()
            assertNotNull(pending)
            assertTrue(Surface.REELS !in pending!!.proposed.blockedSurfaces)
            // Still blocked in the settings the service actually reads.
            assertTrue(Surface.REELS in store.settings.first().blockedSurfaces)
        }
    }

    @Test
    fun blockingOneMoreSurfaceIsATighteningAndAppliesAtOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.setSurfaceBlocked(Surface.SHORTS, true)
        }

        awaitStore("the extra surface to be blocked") {
            Surface.SHORTS in store.settings.first().blockedSurfaces
        }
        runBlocking {
            assertNull(store.pendingChange.first())
            assertTrue(Surface.REELS in store.settings.first().blockedSurfaces)
        }
    }

    @Test
    fun cancellingDropsWhatWasHeldWithoutApplyingIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.proposeAllowanceSettings(locked.copy(quotaMillis = 30 * 60_000))
        }
        awaitStore("the loosening to be armed") { store.pendingChange.first() != null }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.cancelPendingChange()
        }

        awaitStore("the held change to be dropped") { store.pendingChange.first() == null }
        runBlocking {
            assertEquals(15 * 60_000L, store.allowanceSettings.first().quotaMillis)
        }
    }

    @Test
    fun twoLooseningsInARowLeaveOnlyTheSecondHeld() {
        // Every write reads, decides, then writes, and they are launched into a
        // scope that lets them interleave at every suspension point. Before the
        // mutex, two taps in quick succession both read the pre-change value and
        // both armed a change; the first vanished without a trace.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.proposeAllowanceSettings(locked.copy(quotaMillis = 20 * 60_000))
            viewModel.proposeAllowanceSettings(locked.copy(quotaMillis = 30 * 60_000))
        }

        awaitStore("the second loosening to be the one held") {
            store.pendingChange.first()?.proposed?.allowance?.quotaMillis == 30 * 60_000L
        }
        runBlocking {
            assertEquals(15 * 60_000L, store.allowanceSettings.first().quotaMillis)
        }
    }
}
