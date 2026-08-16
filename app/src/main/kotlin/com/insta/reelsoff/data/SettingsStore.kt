package com.insta.reelsoff.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.insta.detection.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BlockSettings(
    val blockReels: Boolean = true,
    val blockExplore: Boolean = true,
) {
    val blockedSurfaces: Set<Surface>
        get() = buildSet {
            if (blockReels) add(Surface.REELS)
            if (blockExplore) add(Surface.EXPLORE)
        }
}

/**
 * The outcome of the service's most recent `RuleSetLoader.load()` call, mirrored
 * into DataStore so the UI — which lives in a different process-scoped component
 * from the accessibility service, and never calls the loader itself — can find
 * out. [error] is null when the active rules (bundled or override) parsed cleanly;
 * non-null means the app is running on a fallback and the user can act on it
 * (typically: fix a hand-edited rules.json override).
 */
data class RuleLoadStatus(
    val source: String = "BUNDLED",
    val error: String? = null,
)

/**
 * What the service's capture session is doing, mirrored into DataStore so the home
 * screen can show it. Before this existed the capture button's only feedback was a
 * line in logcat, so pressing it looked identical to pressing nothing.
 *
 * Timestamps are wall-clock epoch millis, not the service's elapsed-time clock:
 * the UI has to render a countdown against its own `System.currentTimeMillis()`,
 * and only wall clock is comparable across the two.
 *
 * Zero means "not set" — epoch 0 is 1970, never a real value here.
 *
 * The phase is derived rather than stored, so the UI cannot disagree with the
 * numbers it is drawn from: armed but not started means the service is still
 * waiting for Instagram to come forward, and a window whose deadline has passed
 * is finished whether or not the service got a last event to say so.
 */
data class CaptureStatus(
    val armedAtEpochMillis: Long = 0,
    val startedAtEpochMillis: Long = 0,
    val count: Int = 0,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    val settings: Flow<BlockSettings> = context.dataStore.data.map { preferences ->
        BlockSettings(
            blockReels = preferences[BLOCK_REELS] ?: true,
            blockExplore = preferences[BLOCK_EXPLORE] ?: true,
        )
    }

    val ruleLoadStatus: Flow<RuleLoadStatus> = context.dataStore.data.map { preferences ->
        RuleLoadStatus(
            source = preferences[RULE_SOURCE] ?: "BUNDLED",
            error = preferences[RULE_LOAD_ERROR],
        )
    }

    val captureStatus: Flow<CaptureStatus> = context.dataStore.data.map { preferences ->
        CaptureStatus(
            armedAtEpochMillis = preferences[CAPTURE_ARMED_AT] ?: 0,
            startedAtEpochMillis = preferences[CAPTURE_STARTED_AT] ?: 0,
            count = preferences[CAPTURE_COUNT] ?: 0,
        )
    }

    suspend fun setBlockReels(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_REELS] = enabled }
    }

    suspend fun setBlockExplore(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_EXPLORE] = enabled }
    }

    /** Written by the service after every `RuleSetLoader.load()`, read by the UI. */
    suspend fun setRuleLoadStatus(source: String, error: String?) {
        context.dataStore.edit { preferences ->
            preferences[RULE_SOURCE] = source
            if (error != null) {
                preferences[RULE_LOAD_ERROR] = error
            } else {
                preferences.remove(RULE_LOAD_ERROR)
            }
        }
    }

    /** Written by the service as a capture session progresses, read by the UI. */
    suspend fun setCaptureStatus(status: CaptureStatus) {
        context.dataStore.edit { preferences ->
            preferences[CAPTURE_ARMED_AT] = status.armedAtEpochMillis
            preferences[CAPTURE_STARTED_AT] = status.startedAtEpochMillis
            preferences[CAPTURE_COUNT] = status.count
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val BLOCK_REELS = booleanPreferencesKey("block_reels")
        val BLOCK_EXPLORE = booleanPreferencesKey("block_explore")
        val RULE_SOURCE = stringPreferencesKey("rule_source")
        val RULE_LOAD_ERROR = stringPreferencesKey("rule_load_error")
        val CAPTURE_ARMED_AT = longPreferencesKey("capture_armed_at")
        val CAPTURE_STARTED_AT = longPreferencesKey("capture_started_at")
        val CAPTURE_COUNT = intPreferencesKey("capture_count")
    }
}
