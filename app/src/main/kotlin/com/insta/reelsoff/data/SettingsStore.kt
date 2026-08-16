package com.insta.reelsoff.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    val settings: Flow<BlockSettings> = context.dataStore.data.map { preferences ->
        BlockSettings(
            blockReels = preferences[BLOCK_REELS] ?: true,
            blockExplore = preferences[BLOCK_EXPLORE] ?: true,
        )
    }

    suspend fun setBlockReels(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_REELS] = enabled }
    }

    suspend fun setBlockExplore(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_EXPLORE] = enabled }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val BLOCK_REELS = booleanPreferencesKey("block_reels")
        val BLOCK_EXPLORE = booleanPreferencesKey("block_explore")
    }
}
