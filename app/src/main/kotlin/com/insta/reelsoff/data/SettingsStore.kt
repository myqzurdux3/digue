package com.insta.reelsoff.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.insta.detection.Surface
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.PendingChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which surfaces are blocked. A set rather than one boolean per surface: the
 * list grows with every app supported, and the service also derives the packages
 * it declares to Android from exactly this set.
 */
data class BlockSettings(
    val blockedSurfaces: Set<Surface> = setOf(Surface.REELS, Surface.EXPLORE),
)

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

    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<BlockSettings> = context.dataStore.data.map { preferences ->
        val stored = preferences[BLOCKED_SURFACES]
        if (stored != null) {
            // Unknown names are dropped rather than failing: a downgrade must not
            // leave the store unreadable.
            BlockSettings(stored.mapNotNull { name -> Surface.entries.firstOrNull { it.name == name } }.toSet())
        } else {
            // Migration from the two named booleans. Absent means true, which was
            // their default, so a fresh install lands on REELS + EXPLORE.
            buildSet {
                if (preferences[BLOCK_REELS] ?: true) add(Surface.REELS)
                if (preferences[BLOCK_EXPLORE] ?: true) add(Surface.EXPLORE)
            }.let(::BlockSettings)
        }
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

    /**
     * The packages the service actually declared to Android the last time it
     * successfully did so — see `InstagramWatcherService.applyDeclaredPackages`.
     * Empty before the service has ever run, same as an app just installed and
     * never opened: that reads as "nothing declared yet", not as "declares
     * nothing", and the "Service inactif" block already tells the user when the
     * service itself is the reason nothing is declared.
     */
    val declaredPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[DECLARED_PACKAGES] ?: emptySet()
    }

    val allowanceSettings: Flow<AllowanceSettings> = context.dataStore.data.map { preferences ->
        val defaults = AllowanceSettings()
        AllowanceSettings(
            enabled = preferences[ALLOWANCE_ENABLED] ?: defaults.enabled,
            quotaMillis = preferences[ALLOWANCE_QUOTA] ?: defaults.quotaMillis,
            windowStartMinutes = preferences[ALLOWANCE_WINDOW_START] ?: defaults.windowStartMinutes,
            windowEndMinutes = preferences[ALLOWANCE_WINDOW_END] ?: defaults.windowEndMinutes,
            cooldownMillis = preferences[ALLOWANCE_COOLDOWN] ?: defaults.cooldownMillis,
        )
    }

    val allowanceState: Flow<AllowanceState> = context.dataStore.data.map { preferences ->
        AllowanceState(
            day = preferences[ALLOWANCE_DAY] ?: 0,
            consumedMillis = preferences[ALLOWANCE_CONSUMED] ?: 0,
            passOpenedAtEpochMillis = preferences[ALLOWANCE_PASS_OPENED_AT] ?: 0,
        )
    }

    /**
     * Stored as JSON rather than as flat keys: it nests a whole `LockedSettings`,
     * and a half-written set of flat keys would read back as a change nobody
     * armed. Unparseable content reads as "nothing pending" — the strict answer,
     * since a pending change only ever loosens.
     */
    val pendingChange: Flow<PendingChange?> = context.dataStore.data.map { preferences ->
        val raw = preferences[PENDING_CHANGE] ?: return@map null
        runCatching { json.decodeFromString<PendingChange>(raw) }.getOrNull()
    }

    suspend fun setAllowanceSettings(settings: AllowanceSettings) {
        context.dataStore.edit { preferences ->
            preferences[ALLOWANCE_ENABLED] = settings.enabled
            preferences[ALLOWANCE_QUOTA] = settings.quotaMillis
            preferences[ALLOWANCE_WINDOW_START] = settings.windowStartMinutes
            preferences[ALLOWANCE_WINDOW_END] = settings.windowEndMinutes
            preferences[ALLOWANCE_COOLDOWN] = settings.cooldownMillis
        }
    }

    suspend fun setAllowanceState(state: AllowanceState) {
        context.dataStore.edit { preferences ->
            preferences[ALLOWANCE_DAY] = state.day
            preferences[ALLOWANCE_CONSUMED] = state.consumedMillis
            preferences[ALLOWANCE_PASS_OPENED_AT] = state.passOpenedAtEpochMillis
        }
    }

    suspend fun setPendingChange(change: PendingChange?) {
        context.dataStore.edit { preferences ->
            if (change == null) {
                preferences.remove(PENDING_CHANGE)
            } else {
                preferences[PENDING_CHANGE] = json.encodeToString(change)
            }
        }
    }

    /** Test seam: writes content the parser is meant to reject. */
    internal suspend fun writeRawPendingChangeForTest(raw: String) {
        context.dataStore.edit { it[PENDING_CHANGE] = raw }
    }

    /**
     * Test seam: writes the pre-`BLOCKED_SURFACES` key that the migration in
     * [settings] still reads. Nothing in production writes it any more, but an
     * install upgrading from that build has it, and dropping the migration would
     * silently re-enable a surface the user had switched off.
     */
    internal suspend fun writeLegacyBlockExploreForTest(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_EXPLORE] = enabled }
    }

    suspend fun setSurfaceBlocked(surface: Surface, blocked: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[BLOCKED_SURFACES]
                ?: buildSet {
                    if (preferences[BLOCK_REELS] ?: true) add(Surface.REELS.name)
                    if (preferences[BLOCK_EXPLORE] ?: true) add(Surface.EXPLORE.name)
                }
            preferences[BLOCKED_SURFACES] =
                if (blocked) current + surface.name else current - surface.name
        }
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

    /** Written by the service right after a successful `serviceInfo.packageNames` update. */
    suspend fun setDeclaredPackages(packages: Set<String>) {
        context.dataStore.edit { it[DECLARED_PACKAGES] = packages }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        // Written by no one since the move to BLOCKED_SURFACES; still read by the
        // migration above, so an existing install keeps the switches it had.
        val BLOCK_REELS = booleanPreferencesKey("block_reels")
        val BLOCK_EXPLORE = booleanPreferencesKey("block_explore")
        val ALLOWANCE_ENABLED = booleanPreferencesKey("allowance_enabled")
        val ALLOWANCE_QUOTA = longPreferencesKey("allowance_quota_millis")
        val ALLOWANCE_WINDOW_START = intPreferencesKey("allowance_window_start_minutes")
        val ALLOWANCE_WINDOW_END = intPreferencesKey("allowance_window_end_minutes")
        val ALLOWANCE_COOLDOWN = longPreferencesKey("allowance_cooldown_millis")
        val ALLOWANCE_DAY = longPreferencesKey("allowance_day")
        val ALLOWANCE_CONSUMED = longPreferencesKey("allowance_consumed_millis")
        val ALLOWANCE_PASS_OPENED_AT = longPreferencesKey("allowance_pass_opened_at")
        val PENDING_CHANGE = stringPreferencesKey("pending_change")
        val BLOCKED_SURFACES = stringSetPreferencesKey("blocked_surfaces")
        val RULE_SOURCE = stringPreferencesKey("rule_source")
        val RULE_LOAD_ERROR = stringPreferencesKey("rule_load_error")
        val CAPTURE_ARMED_AT = longPreferencesKey("capture_armed_at")
        val CAPTURE_STARTED_AT = longPreferencesKey("capture_started_at")
        val CAPTURE_COUNT = intPreferencesKey("capture_count")
        val DECLARED_PACKAGES = stringSetPreferencesKey("declared_packages")
    }
}
