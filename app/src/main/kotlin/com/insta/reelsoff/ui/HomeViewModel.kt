package com.insta.reelsoff.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.insta.reelsoff.data.AppDatabase
import com.insta.reelsoff.data.BlockSettings
import com.insta.reelsoff.data.DailyCount
import com.insta.reelsoff.data.SettingsStore
import com.insta.reelsoff.data.dailyCounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

const val HISTORY_DAYS = 14

data class HomeUiState(
    val serviceEnabled: Boolean = false,
    val settings: BlockSettings = BlockSettings(),
    val history: List<DailyCount> = emptyList(),
    val degraded: Boolean = false,
) {
    val todayReels: Int get() = history.lastOrNull()?.reels ?: 0
    val todayExplore: Int get() = history.lastOrNull()?.explore ?: 0
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val dao = AppDatabase.get(application).blockEventDao()
    private val serviceEnabled = MutableStateFlow(false)

    /** Called from onResume: the user leaves the app to flip the system toggle. */
    fun refreshServiceStatus() {
        serviceEnabled.value = isServiceEnabled(getApplication())
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val historySinceMillis: Long
        get() = LocalDate.now(zone)
            .minusDays((HISTORY_DAYS - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    val uiState: StateFlow<HomeUiState> = combine(
        serviceEnabled,
        settingsStore.settings,
        dao.observeSince(historySinceMillis),
    ) { enabled, settings, events ->
        HomeUiState(
            serviceEnabled = enabled,
            settings = settings,
            history = dailyCounts(events, zone, LocalDate.now(zone), HISTORY_DAYS),
            degraded = isDegraded(events),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setBlockReels(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBlockReels(enabled) }
    }

    fun setBlockExplore(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBlockExplore(enabled) }
    }
}
