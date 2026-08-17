package com.insta.reelsoff.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.insta.detection.Surface
import com.insta.reelsoff.data.AppDatabase
import com.insta.reelsoff.data.BlockEvent
import com.insta.reelsoff.data.BlockSettings
import com.insta.reelsoff.data.CaptureStatus
import com.insta.reelsoff.data.DailyCount
import com.insta.reelsoff.data.DailyWatched
import com.insta.reelsoff.data.PassEvent
import com.insta.reelsoff.data.RuleLoadStatus
import com.insta.reelsoff.data.SettingsStore
import com.insta.reelsoff.data.dailyCounts
import com.insta.reelsoff.data.dailyWatched
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.LockedSettings
import com.insta.reelsoff.service.PendingChange
import com.insta.reelsoff.service.armChange
import com.insta.reelsoff.service.closureOf
import com.insta.reelsoff.service.forcedClosureOf
import com.insta.reelsoff.service.maturedProposal
import com.insta.reelsoff.service.openPass
import com.insta.reelsoff.service.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

const val HISTORY_DAYS = 14
private const val TAG = "HomeViewModel"

/**
 * How often the history window's lower bound is recomputed. Chosen well
 * under a day so an app left open across midnight (F10) picks up the new
 * 14-day window within one tick rather than staying pinned to the day the
 * ViewModel was constructed.
 */
private const val WINDOW_RECOMPUTE_INTERVAL_MILLIS = 15 * 60 * 1000L

data class HomeUiState(
    val serviceEnabled: Boolean = false,
    val settings: BlockSettings = BlockSettings(),
    val history: List<DailyCount> = emptyList(),
    val degraded: Boolean = false,
    /** Non-null when the service is running on fallback rules; see F1. */
    val ruleLoadError: String? = null,
    val captureStatus: CaptureStatus = CaptureStatus(),
    /** Read on resume: the user can install or remove an app while this screen is closed. */
    val installedPackages: Set<String> = emptySet(),
    /**
     * The packages the service actually declared to Android the last time it
     * succeeded — published by `InstagramWatcherService.applyDeclaredPackages`,
     * not recomputed here. Deliberately not filtered by [installedPackages]:
     * this is what "Applications observées" reports, and it must reflect what
     * really happened, not what should have happened had the assignment
     * succeeded — those can disagree if `serviceInfo.packageNames =` throws, or
     * if the service's cached rule set is stale relative to a hand-edited
     * override file the ViewModel would otherwise re-read on its own.
     */
    val declaredPackages: Set<String> = emptySet(),
    /** Watched time per day, same 14-day window and same order as [history]. */
    val watched: List<DailyWatched> = emptyList(),
) {
    val todayTotal: Int get() = history.lastOrNull()?.total ?: 0

    /** Time spent inside a pass today, in millis. */
    val todayWatchedMillis: Long get() = watched.lastOrNull()?.millis ?: 0

    val watchedTotalMillis: Long get() = watched.sumOf { it.millis }
}

/**
 * The surfaces that carry a user-facing switch. `Surface.OTHER` is not one, and
 * is deliberately not derived from `Surface.entries`: a new blockable surface
 * must be added here on purpose, and the instrumented test that promises "every
 * surface" names the same list for the same reason.
 */
private val BLOCKABLE_SURFACES = listOf(
    Surface.REELS,
    Surface.EXPLORE,
    Surface.SHORTS,
    Surface.SPOTLIGHT,
    Surface.DISCOVER,
)

private val ALL_KNOWN_PACKAGES = setOf(
    "com.instagram.android",
    "com.google.android.youtube",
    "com.google.android.apps.youtube.kids",
    "app.revanced.android.youtube",
    "com.snapchat.android",
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val dao = AppDatabase.get(application).blockEventDao()
    private val passDao = AppDatabase.get(application).passEventDao()
    private val serviceEnabled = MutableStateFlow(false)
    private val installedPackages = MutableStateFlow(emptySet<String>())

    /** Called from onResume: the user leaves the app to flip the system toggle. */
    fun refreshServiceStatus() {
        serviceEnabled.value = isServiceEnabled(getApplication())
    }

    /**
     * Called from onResume: the user can install or remove an app while this
     * screen is closed. Dispatched onto viewModelScope (which defaults to
     * Dispatchers.Main.immediate) so the five PackageManager IPCs below don't
     * run synchronously on the caller's thread — onResume is the main thread.
     */
    fun refreshInstalledPackages() {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = getApplication<Application>().packageManager
            val installed = ALL_KNOWN_PACKAGES.filter { candidate ->
                runCatching { manager.getPackageInfo(candidate, 0) }.isSuccess
            }.toSet()
            installedPackages.value = installed
        }
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun historySinceMillis(): Long =
        LocalDate.now(zone)
            .minusDays((HISTORY_DAYS - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    // The history window is recomputed per emission (F10), not once at construction:
    // a plain val captured when the ViewModel is built would keep yesterday's 14-day
    // window for an app left open across midnight. windowTick re-derives
    // historySinceMillis() on a timer and restarts the DB query with the fresh bound;
    // a boolean trigger (e.g. serviceEnabled) would not do, since a StateFlow only
    // re-emits on a value *change* and resuming with the same enabled state would
    // silently skip the recompute.
    private val windowTick = flow {
        while (true) {
            emit(Unit)
            delay(WINDOW_RECOMPUTE_INTERVAL_MILLIS)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val events = windowTick.flatMapLatest { dao.observeSince(historySinceMillis()) }

    // Driven by the same tick as `events`, so the two histories are always cut on
    // the same 14-day boundary and can be read index for index.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val passes = windowTick.flatMapLatest { passDao.observeSince(historySinceMillis()) }

    // combine's typed overloads stop at five flows; this screen needs eight, so the
    // vararg form is used instead, indexed positionally against the argument order below.
    // Nothing here is type-checked: two Set<String> flows sit next to each other, and
    // swapping any two indices compiles and runs. Re-read this table against the
    // argument list whenever either changes.
    //   0 serviceEnabled                  -> Boolean
    //   1 settingsStore.settings          -> BlockSettings
    //   2 events                          -> List<BlockEvent>
    //   3 settingsStore.ruleLoadStatus    -> RuleLoadStatus
    //   4 settingsStore.captureStatus     -> CaptureStatus
    //   5 installedPackages               -> Set<String>
    //   6 settingsStore.declaredPackages  -> Set<String>
    //   7 passes                          -> List<PassEvent>
    val uiState: StateFlow<HomeUiState> = combine(
        serviceEnabled,
        settingsStore.settings,
        events,
        settingsStore.ruleLoadStatus,
        settingsStore.captureStatus,
        installedPackages,
        settingsStore.declaredPackages,
        passes,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val enabled = values[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val settings = values[1] as BlockSettings
        @Suppress("UNCHECKED_CAST")
        val dayEvents = values[2] as List<BlockEvent>
        @Suppress("UNCHECKED_CAST")
        val ruleLoadStatus = values[3] as RuleLoadStatus
        @Suppress("UNCHECKED_CAST")
        val captureStatus = values[4] as CaptureStatus
        @Suppress("UNCHECKED_CAST")
        val installed = values[5] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val declared = values[6] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val passEvents = values[7] as List<PassEvent>
        HomeUiState(
            serviceEnabled = enabled,
            settings = settings,
            history = dailyCounts(dayEvents, zone, LocalDate.now(zone), HISTORY_DAYS),
            degraded = isDegraded(dayEvents) || ruleLoadStatus.error != null,
            ruleLoadError = ruleLoadStatus.error,
            captureStatus = captureStatus,
            installedPackages = installed,
            declaredPackages = declared,
            watched = dailyWatched(passEvents, zone, LocalDate.now(zone), HISTORY_DAYS),
        )
    }
        // Both DataStore (IOException) and Room (SQLiteException) can throw out of this
        // combined flow. MainActivity and the accessibility service share one process, so
        // an uncaught throw here would take the blocker down with the UI (F5) — degrade to
        // a safe default screen instead.
        .catch { e ->
            Log.e(TAG, "state combination failed", e)
            emit(HomeUiState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * The quota's own state, recomputed every second.
     *
     * Deliberately its own flow rather than three more arguments to [uiState].
     * Time passing is not an emission from any DataStore flow, so a countdown
     * folded into that combine simply froze at the value it had when the pass
     * opened — measured on the device, where "Pass ouvert — 59 s" stayed at 59 s
     * for minutes. Recomposing the panel could not fix it: the panel was
     * redrawing the same stale numbers.
     *
     * And it must not be *inside* [uiState] either: that combine also rebuilds
     * the 14-day chart from every event in the window, which has no business
     * running once a second.
     */
    val allowance: StateFlow<AllowanceUiState> = combine(
        settingsStore.allowanceSettings,
        settingsStore.allowanceState,
        settingsStore.pendingChange,
        settingsStore.settings,
        flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        },
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val stored = values[0] as AllowanceSettings
        @Suppress("UNCHECKED_CAST")
        val state = values[1] as AllowanceState
        @Suppress("UNCHECKED_CAST")
        val pending = values[2] as PendingChange?
        @Suppress("UNCHECKED_CAST")
        val blockSettings = values[3] as BlockSettings
        allowanceUiState(
            stored = stored,
            state = state,
            pending = pending,
            blockedSurfaces = blockSettings.blockedSurfaces,
            nowEpochMillis = System.currentTimeMillis(),
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
            zone = zone,
        )
    }
        // Same reason as uiState's catch: this screen shares a process with the
        // accessibility service, so an uncaught DataStore failure here would take
        // the blocker down with the UI. A default AllowanceUiState reads as "no
        // quota", which is the strict side.
        .catch { e ->
            Log.e(TAG, "allowance state combination failed", e)
            emit(AllowanceUiState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllowanceUiState())

    /**
     * Writes a matured pending change into the store and clears it.
     *
     * Readers do not depend on this — `effectiveSettings` derives the in-force
     * values on every read, which is what makes a matured change apply even if
     * the process died before it could be written back. What this exists for is
     * to stop the store and the in-force values from drifting apart, because the
     * lock compares a *proposal* against the store.
     *
     * Idempotent, and a no-op when nothing is pending or nothing has matured.
     */
    private suspend fun commitMaturedChange() {
        val proposal = maturedProposal(
            pending = settingsStore.pendingChange.first(),
            nowEpochMillis = System.currentTimeMillis(),
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
        ) ?: return
        settingsStore.setAllowanceSettings(proposal.allowance)
        // Written surface by surface: setSurfaceBlocked is the only writer of that
        // key, and it carries the migration from the two old booleans. Every
        // blockable surface is named, so one that is absent from the proposal is
        // actually switched off rather than left as it was.
        for (surface in BLOCKABLE_SURFACES) {
            settingsStore.setSurfaceBlocked(surface, surface in proposal.blockedSurfaces)
        }
        settingsStore.setPendingChange(null)
    }

    /**
     * Called on resume: a change can mature while the app is closed, and the
     * store would otherwise stay behind the values already in force.
     */
    fun commitAnyMaturedChange() {
        viewModelScope.launch { commitMaturedChange() }
    }

    /**
     * Every settings write in this class goes through here, so what applies at
     * once and what waits is decided in exactly one place.
     *
     * [applyTightening] runs only when the change does not loosen anything; it
     * is passed rather than performed here because the two writers touch
     * different keys — the quota's own fields, and the surface switches.
     */
    private suspend fun writeThroughLock(
        proposed: (LockedSettings) -> LockedSettings,
        applyTightening: suspend () -> Unit,
    ) {
        // Before comparing anything: a matured change is already in force as far
        // as every reader is concerned, but it is not in the store yet. Comparing
        // against the stale stored value would measure the proposal against the
        // wrong baseline and re-arm a delay the user has already served — the
        // loosening they had earned would silently roll back.
        commitMaturedChange()
        val current = LockedSettings(
            settingsStore.allowanceSettings.first(),
            settingsStore.settings.first().blockedSurfaces,
        )
        val armed = armChange(
            current = current,
            proposed = proposed(current),
            nowEpochMillis = System.currentTimeMillis(),
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        if (armed == null) {
            applyTightening()
            // A tightening supersedes anything held: leaving a loosening armed
            // past it would undo the tightening on its own, later, silently.
            settingsStore.setPendingChange(null)
        } else {
            settingsStore.setPendingChange(armed)
        }
    }

    fun setSurfaceBlocked(surface: Surface, blocked: Boolean) {
        viewModelScope.launch {
            writeThroughLock(
                proposed = { current ->
                    current.copy(
                        blockedSurfaces = if (blocked) {
                            current.blockedSurfaces + surface
                        } else {
                            current.blockedSurfaces - surface
                        },
                    )
                },
                applyTightening = { settingsStore.setSurfaceBlocked(surface, blocked) },
            )
        }
    }

    fun proposeAllowanceSettings(proposed: AllowanceSettings) {
        viewModelScope.launch {
            writeThroughLock(
                proposed = { current -> current.copy(allowance = proposed) },
                applyTightening = { settingsStore.setAllowanceSettings(proposed) },
            )
        }
    }

    /**
     * Settles first, so a pass that expired while the screen was closed is
     * banked rather than reopened — [openPass] refuses an already-open pass, and
     * an expired one still carries a nonzero opening stamp.
     */
    fun openPass() {
        viewModelScope.launch {
            // Same reason as in writeThroughLock: a matured change may have
            // widened the window or raised the quota, and reading the stale store
            // would refuse a pass the user is entitled to.
            commitMaturedChange()
            val settings = settingsStore.allowanceSettings.first()
            val now = System.currentTimeMillis()
            val settled = settle(settings, settingsStore.allowanceState.first(), now, zone)
            settingsStore.setAllowanceState(openPass(settings, settled, now, zone))
        }
    }

    /**
     * Closes the pass and records it. Shares the duration arithmetic with the
     * service's expiry path, so the button and a pass running out cannot disagree
     * about what was watched.
     */
    fun closePass() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val closure = forcedClosureOf(
                settingsStore.allowanceSettings.first(),
                settingsStore.allowanceState.first(),
                now,
                zone,
            ) ?: return@launch
            settingsStore.setAllowanceState(closure.state)
            if (closure.durationMillis > 0) {
                passDao.insert(PassEvent(epochMillis = now, durationMillis = closure.durationMillis))
            }
        }
    }

    /** Cancelling a held loosening is itself a tightening, so it lands at once. */
    fun cancelPendingChange() {
        viewModelScope.launch { settingsStore.setPendingChange(null) }
    }
}
