package com.insta.reelsoff.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val BLOCK_REELS = booleanPreferencesKey("block_reels")
        val BLOCK_EXPLORE = booleanPreferencesKey("block_explore")
        val RULE_SOURCE = stringPreferencesKey("rule_source")
        val RULE_LOAD_ERROR = stringPreferencesKey("rule_load_error")
    }
}
