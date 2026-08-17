package com.insta.reelsoff.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.insta.detection.Surface
import com.insta.reelsoff.data.AppDatabase
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
import com.insta.reelsoff.service.PassClosure
import com.insta.reelsoff.service.PendingChange
import com.insta.reelsoff.service.armChange
import com.insta.reelsoff.service.closureOf
import com.insta.reelsoff.service.deleteCaptures
import com.insta.reelsoff.service.forcedClosureOf
import com.insta.reelsoff.service.listCaptures
import com.insta.reelsoff.service.maturedProposal
import com.insta.reelsoff.service.openPass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Capture files sitting on disk right now — what the delete button offers to remove. */
    val captures: CapturesOnDisk = CapturesOnDisk(),
) {
    val todayTotal: Int get() = history.lastOrNull()?.total ?: 0

    /** Time spent inside a pass today, in millis. */
    val todayWatchedMillis: Long get() = watched.lastOrNull()?.millis ?: 0

    val watchedTotalMillis: Long get() = watched.sumOf { it.millis }
}

/**
 * How many capture files are on disk and what they weigh.
 *
 * Read from the file system rather than counted from `CaptureStatus`: that one
 * says what the last session wrote, which stops being true the moment anything
 * is deleted — by the next arming, by the button, or by the user's own file
 * manager. The offer to delete has to describe what is actually there.
 */
data class CapturesOnDisk(val count: Int = 0, val bytes: Long = 0)

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
    private val captures = MutableStateFlow(CapturesOnDisk())

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

    /**
     * Called on resume, and again after a deletion.
     *
     * Off the main thread: this stats every file in the capture directory, and
     * onResume runs on the main thread. Same shape and same reason as
     * [refreshInstalledPackages].
     */
    fun refreshCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            captures.value = runCatching {
                val files = listCaptures(getApplication())
                CapturesOnDisk(files.size, files.sumOf { it.length() })
            }.getOrElse {
                Log.e(TAG, "could not list captures", it)
                // Reporting nothing is the honest failure here: the button that
                // reads this offers to delete, and offering to delete files we
                // could not even count would be worse than staying quiet.
                CapturesOnDisk()
            }
        }
    }

    /**
     * Deletes every capture this app wrote, on the user's say-so.
     *
     * The service already clears earlier sessions each time a capture is armed;
     * this is the way to clear the last one too, without arming anything. Both go
     * through the same rule about which files are ours, so neither can reach a
     * file that is not a capture.
     *
     * Nothing here needs the accessibility service — the files belong to the app,
     * not to the service — so this still works when it is switched off, which is
     * exactly when someone is most likely to be tidying up.
     */
    fun deleteAllCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { deleteCaptures(getApplication()) }
                .onSuccess { Log.i(TAG, "deleted $it captures") }
                .onFailure { Log.e(TAG, "could not delete captures", it) }
            // The session record goes with the files. Measured on the device: with
            // it left alone the screen kept saying "Terminé : 3 instantanés
            // enregistrés" over an empty directory, which sends the user looking
            // for files that are not there. Resetting it puts the capture control
            // back to its idle wording, which is now the truth.
            runCatching { settingsStore.setCaptureStatus(CaptureStatus()) }
                .onFailure { Log.e(TAG, "could not reset the capture status", it) }
            refreshCaptures()
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

    /** The fourteen-day chart, and the one banner derived from the same rows. */
    private data class HistorySlice(
        val history: List<DailyCount>,
        val watched: List<DailyWatched>,
        val degradedByTier: Boolean,
    )

    /** Everything about how the app and the service are currently set up. */
    private data class StatusSlice(
        val serviceEnabled: Boolean,
        val settings: BlockSettings,
        val ruleLoadStatus: RuleLoadStatus,
        val captureStatus: CaptureStatus,
        val captures: CapturesOnDisk,
    )

    /** Which apps are on the phone, and which the service may actually observe. */
    private data class PackagesSlice(
        val installed: Set<String>,
        val declared: Set<String>,
    )

    // Three typed groups rather than one eight-argument combine.
    //
    // combine's typed overloads stop at five flows, so eight forced the vararg
    // form — an Array<Any?> read by index, with eight unchecked casts and a table
    // in a comment for a type system. Two Set<String> sat next to each other, and
    // swapping their indices compiled and ran. Nesting typed combines makes that
    // mistake impossible to write instead of merely documented.
    //
    // It also fixes what the shape cost at runtime. The chart is rebuilt from
    // every event in the window, and in one flat combine that happened on every
    // emission of all eight sources — including captureStatus, which the service
    // writes every three seconds for the length of a capture, and which cannot
    // change a single bar. Now the rebuild lives in `historySlice` and only its
    // own two sources can trigger it; the others re-use the value already
    // computed. Same reasoning that moved the countdown out of here entirely.
    private val historySlice: Flow<HistorySlice> = combine(events, passes) { dayEvents, passEvents ->
        val today = LocalDate.now(zone)
        HistorySlice(
            history = dailyCounts(dayEvents, zone, today, HISTORY_DAYS),
            watched = dailyWatched(passEvents, zone, today, HISTORY_DAYS),
            degradedByTier = isDegraded(dayEvents),
        )
    }

    // Five flows, which is exactly where combine's typed overloads stop — a sixth
    // would force the untyped vararg form back, and that is the shape this file
    // was just rid of. Split the slice rather than reach for it.
    private val statusSlice: Flow<StatusSlice> = combine(
        serviceEnabled,
        settingsStore.settings,
        settingsStore.ruleLoadStatus,
        settingsStore.captureStatus,
        captures,
        ::StatusSlice,
    )

    private val packagesSlice: Flow<PackagesSlice> =
        combine(installedPackages, settingsStore.declaredPackages, ::PackagesSlice)

    val uiState: StateFlow<HomeUiState> = combine(
        historySlice,
        statusSlice,
        packagesSlice,
    ) { history, status, packages ->
        HomeUiState(
            serviceEnabled = status.serviceEnabled,
            settings = status.settings,
            history = history.history,
            // The two causes are folded here rather than in the slice: one is a
            // property of the recent blocks, the other of the last rule load.
            degraded = history.degradedByTier || status.ruleLoadStatus.error != null,
            ruleLoadError = status.ruleLoadStatus.error,
            captureStatus = status.captureStatus,
            captures = status.captures,
            installedPackages = packages.installed,
            declaredPackages = packages.declared,
            watched = history.watched,
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
     * Serialises every settings write.
     *
     * All of them read the store, decide, then write — and they are launched from
     * five different UI gestures into a scope that lets them interleave at every
     * suspension point. Two taps in quick succession therefore both read the
     * pre-change value and both wrote a `pending_change`, and the first one
     * vanished without a trace. The shortest way to see it was to arm a cooldown
     * and press the window stepper twice.
     *
     * Held across the whole read-decide-write sequence, not just the write: it is
     * the read that goes stale.
     */
    private val writes = Mutex()

    /**
     * Records what a closure was worth, when it was worth anything.
     *
     * Both the "Fermer maintenant" button and the settling that happens when a
     * pass is reopened go through here, so a duration reaches the history the same
     * way whichever ended the pass. A zero — a pass carried over from a day that
     * no longer has a budget — is not a row.
     */
    private suspend fun bank(closure: PassClosure?, nowEpochMillis: Long) {
        if (closure == null || closure.durationMillis <= 0) return
        passDao.insert(
            PassEvent(epochMillis = nowEpochMillis, durationMillis = closure.durationMillis),
        )
    }

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
     *
     * The writes below are not one transaction — up to seven separate DataStore
     * edits — and the order is what makes an interruption safe rather than a
     * migration half-applied. `pending_change` is cleared **last**, and every
     * reader derives the settings in force through `effectiveSettings`, so as
     * long as the held change is still there and has matured, its values are the
     * ones in force whatever else did or did not land. A process killed halfway
     * repairs itself on the next call. Do not reorder these.
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
        viewModelScope.launch { writes.withLock { commitMaturedChange() } }
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
    ) = writes.withLock {
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
     * Settles first, so a pass that expired while the screen was closed is banked
     * rather than reopened — [openPass] refuses an already-open pass, and an
     * expired one still carries a nonzero opening stamp.
     *
     * Settling used to go through `settle`, which banks the elapsed time into the
     * state and returns — writing no `pass_event` row. The minutes were charged
     * against the quota, correctly, and then vanished from the chart. The service
     * covers the ordinary case, since it notices the expiry at its next event; it
     * cannot cover this one, because a user who left the watched app before the
     * pass ran out sends the service no event at all, and then it is this screen
     * that settles first. The figure the whole quota exists to move was the one
     * being under-reported.
     *
     * Half the gap remains, and deliberately: a pass carried over from an earlier
     * day still records nothing. `closePass` discards its time because the day it
     * belonged to no longer has a budget to charge, and putting those minutes on
     * today would be a worse lie than omitting them.
     */
    fun openPass() {
        viewModelScope.launch {
            writes.withLock {
                // Same reason as in writeThroughLock: a matured change may have
                // widened the window or raised the quota, and reading the stale
                // store would refuse a pass the user is entitled to.
                commitMaturedChange()
                val settings = settingsStore.allowanceSettings.first()
                val now = System.currentTimeMillis()
                val stored = settingsStore.allowanceState.first()

                val closure = closureOf(settings, stored, now, zone)
                val settled = closure?.state ?: stored

                // State first, row second — the same order as closePass, and the
                // order matters. Banking first and then failing to write the state
                // would leave a pass that is still open and already expired in the
                // store: the service would settle it again at its next event and
                // insert a SECOND row for the same minutes. This way a failure
                // between the two loses the row, which under-reports — the side
                // that does not invent watched time.
                settingsStore.setAllowanceState(openPass(settings, settled, now, zone))
                bank(closure, now)
            }
        }
    }

    /**
     * Closes the pass and records it. Shares the duration arithmetic with the
     * service's expiry path, so the button and a pass running out cannot disagree
     * about what was watched.
     */
    fun closePass() {
        viewModelScope.launch {
            writes.withLock {
                val now = System.currentTimeMillis()
                val closure = forcedClosureOf(
                    settingsStore.allowanceSettings.first(),
                    settingsStore.allowanceState.first(),
                    now,
                    zone,
                ) ?: return@withLock
                settingsStore.setAllowanceState(closure.state)
                bank(closure, now)
            }
        }
    }

    /** Cancelling a held loosening is itself a tightening, so it lands at once. */
    fun cancelPendingChange() {
        viewModelScope.launch { writes.withLock { settingsStore.setPendingChange(null) } }
    }
}
