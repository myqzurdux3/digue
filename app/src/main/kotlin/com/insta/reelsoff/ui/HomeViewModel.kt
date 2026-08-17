package com.insta.reelsoff.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.insta.detection.Surface
import com.insta.reelsoff.data.AppDatabase
import com.insta.reelsoff.data.BlockEvent
import com.insta.reelsoff.data.BlockSettings
import com.insta.reelsoff.data.CaptureStatus
import com.insta.reelsoff.data.DailyCount
import com.insta.reelsoff.data.RuleLoadStatus
import com.insta.reelsoff.data.SettingsStore
import com.insta.reelsoff.data.dailyCounts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
) {
    val todayTotal: Int get() = history.lastOrNull()?.total ?: 0
}

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
    private val serviceEnabled = MutableStateFlow(false)
    private val installedPackages = MutableStateFlow(emptySet<String>())

    /** Called from onResume: the user leaves the app to flip the system toggle. */
    fun refreshServiceStatus() {
        serviceEnabled.value = isServiceEnabled(getApplication())
    }

    /** Called from onResume: the user can install or remove an app while this screen is closed. */
    fun refreshInstalledPackages() {
        val manager = getApplication<Application>().packageManager
        installedPackages.value = ALL_KNOWN_PACKAGES.filter { candidate ->
            runCatching { manager.getPackageInfo(candidate, 0) }.isSuccess
        }.toSet()
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

    // combine's typed overloads stop at five flows; this screen now needs six, so the
    // vararg form is used instead, indexed positionally against the argument order below.
    val uiState: StateFlow<HomeUiState> = combine(
        serviceEnabled,
        settingsStore.settings,
        events,
        settingsStore.ruleLoadStatus,
        settingsStore.captureStatus,
        installedPackages,
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
        HomeUiState(
            serviceEnabled = enabled,
            settings = settings,
            history = dailyCounts(dayEvents, zone, LocalDate.now(zone), HISTORY_DAYS),
            degraded = isDegraded(dayEvents) || ruleLoadStatus.error != null,
            ruleLoadError = ruleLoadStatus.error,
            captureStatus = captureStatus,
            installedPackages = installed,
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

    fun setSurfaceBlocked(surface: Surface, blocked: Boolean) {
        viewModelScope.launch { settingsStore.setSurfaceBlocked(surface, blocked) }
    }
}
