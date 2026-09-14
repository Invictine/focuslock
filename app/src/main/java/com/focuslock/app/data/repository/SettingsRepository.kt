package com.focuslock.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

private val Context.dataStore by preferencesDataStore(name = "focuslock_settings")

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    // Corruption-hardened DataStore access (crash fix): DataStore throws on a corrupt
    // file; every flow and first()/edit() call here previously died with it. Reads
    // fall back to empty prefs (all callers already default missing keys) and writes
    // are skipped with a log so callers keep running until the file recovers.
    private suspend fun readSettingsPrefs(): Preferences =
        try {
            context.dataStore.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SettingsRepo", "settings DataStore read failed; using defaults", e)
            emptyPreferences()
        }

    /** Returns true when the edit committed; false means it was skipped (failure logged). */
    private suspend fun editSettings(transform: (MutablePreferences) -> Unit): Boolean =
        try {
            context.dataStore.edit(transform)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SettingsRepo", "settings DataStore write failed; change skipped", e)
            false
        }

    // In-memory mirrors of the hot DataStore reads (same pattern as AppLimitsRepository).
    // Each source flow feeds exactly one MutableStateFlow via `onEach`; hot-path reads
    // consult the cache and only fall back to DataStore until the cache is warm.
    private val _blockedApps = MutableStateFlow<List<BlockedApp>>(emptyList())
    private val blockedAppsLoaded = MutableStateFlow(false)
    private val _blockedWebsites = MutableStateFlow<List<BlockedWebsite>>(emptyList())
    private val blockedWebsitesLoaded = MutableStateFlow(false)
    private val _lockdownMode = MutableStateFlow(false)
    private val lockdownModeLoaded = MutableStateFlow(false)
    private val _nukeActive = MutableStateFlow(false)
    private val nukeActiveLoaded = MutableStateFlow(false)

    /** One-shot guard so read paths never trigger the legacy lockdown migration write. */
    private val lockdownMigrationChecked = AtomicBoolean(false)

    object PreferencesKeys {
        val BLOCKED_APPS_JSON = stringPreferencesKey("blocked_apps_json")
        val BLOCKED_WEBSITES_JSON = stringPreferencesKey("blocked_websites_json")
        val BLOCKED_APPS_UPDATED_AT = longPreferencesKey("blocked_apps_updated_at")
        val BLOCKED_WEBSITES_UPDATED_AT = longPreferencesKey("blocked_websites_updated_at")
        val WORK_RATIO = intPreferencesKey("work_ratio") // e.g. 4 => 4 min work = 1 min scroll
        val WORK_RATIO_UPDATED_AT = longPreferencesKey("work_ratio_updated_at")
        val TASK_COMPLETION_BONUS = intPreferencesKey("task_completion_bonus")
        val TASK_COMPLETION_BONUS_UPDATED_AT = longPreferencesKey("task_completion_bonus_updated_at")
        val STRICT_MODE = booleanPreferencesKey("strict_mode")
        val STRICT_MODE_ENABLED_AT = longPreferencesKey("strict_mode_enabled_at")
        val STRICT_MODE_UPDATED_AT = longPreferencesKey("strict_mode_updated_at")
        // Renamed strict -> lockdown. New canonical keys; old strict_* keys kept for back-compat read/migration.
        val LOCKDOWN_MODE = booleanPreferencesKey("lockdown_mode")
        val LOCKDOWN_MODE_ENABLED_AT = longPreferencesKey("lockdown_mode_enabled_at")
        val LOCKDOWN_MODE_UPDATED_AT = longPreferencesKey("lockdown_mode_updated_at")
        // Boundaries lock: when true, Boundaries UI must not allow removing blocked apps/websites.
        val BOUNDARIES_LOCK = booleanPreferencesKey("boundaries_lock")
        val BOUNDARIES_LOCK_UPDATED_AT = longPreferencesKey("boundaries_lock_updated_at")
        val NUKE_ACTIVE = booleanPreferencesKey("nuke_active")
        val NUKE_STARTED_AT = longPreferencesKey("nuke_started_at")
        val NUKE_MEDITATION_DONE_AT = longPreferencesKey("nuke_meditation_done_at")

        val OAUTH_STATE = stringPreferencesKey("oauth_state")
        val OAUTH_STARTED_AT = longPreferencesKey("oauth_started_at")

        // TickTick OAuth & API
        val TICKTICK_ACCESS_TOKEN = stringPreferencesKey("ticktick_access_token")
        val TICKTICK_REFRESH_TOKEN = stringPreferencesKey("ticktick_refresh_token")
        val TICKTICK_TOKEN_EXPIRES_AT = longPreferencesKey("ticktick_token_expires_at")
        val TICKTICK_CLIENT_ID = stringPreferencesKey("ticktick_client_id")
        val TICKTICK_CLIENT_SECRET = stringPreferencesKey("ticktick_client_secret")
        val TICKTICK_USER_NAME = stringPreferencesKey("ticktick_user_name")
        val TICKTICK_NOTIFICATION_ENABLED = booleanPreferencesKey("ticktick_notification_enabled")

        // Daily dashboard goals
        val FOCUS_GOAL_MINUTES = intPreferencesKey("focus_goal_minutes")
        val DAILY_TASKS_GOAL = intPreferencesKey("daily_tasks_goal")

        // Daily reminder notification
        val DAILY_REMINDER_ENABLED = booleanPreferencesKey("daily_reminder_enabled")
        val DAILY_REMINDER_MINUTE_OF_DAY = intPreferencesKey("daily_reminder_minute_of_day")
    }

    // Apps Flow
    val blockedAppsFlow: Flow<List<BlockedApp>> = context.dataStore.data
        .map { preferences -> decodeBlockedApps(preferences[PreferencesKeys.BLOCKED_APPS_JSON]) }
        .onEach { apps ->
            _blockedApps.value = apps
            blockedAppsLoaded.value = true
        }
        // DataStore corruption fallback: default boundaries instead of a dead flow.
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w("SettingsRepo", "blockedAppsFlow failed; using defaults", e)
            val fallback = decodeBlockedApps(null)
            _blockedApps.value = fallback
            blockedAppsLoaded.value = true
            emit(fallback)
        }

    // Websites Flow
    val blockedWebsitesFlow: Flow<List<BlockedWebsite>> = context.dataStore.data
        .map { preferences -> decodeBlockedWebsites(preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON]) }
        .onEach { websites ->
            _blockedWebsites.value = websites
            blockedWebsitesLoaded.value = true
        }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w("SettingsRepo", "blockedWebsitesFlow failed; using defaults", e)
            val fallback = decodeBlockedWebsites(null)
            _blockedWebsites.value = fallback
            blockedWebsitesLoaded.value = true
            emit(fallback)
        }

    private fun decodeBlockedApps(raw: String?): List<BlockedApp> = try {
        if (raw.isNullOrBlank()) BlockedApp.DEFAULT_DOOMSCROLL_APPS else json.decodeFromString(raw)
    } catch (_: Exception) {
        BlockedApp.DEFAULT_DOOMSCROLL_APPS
    }

    private fun decodeBlockedWebsites(raw: String?): List<BlockedWebsite> = try {
        if (raw.isNullOrBlank()) BlockedWebsite.DEFAULT_BLOCKED_WEBSITES else json.decodeFromString(raw)
    } catch (_: Exception) {
        BlockedWebsite.DEFAULT_BLOCKED_WEBSITES
    }

    // Scalar flows: every map is wrapped with a corruption fallback so a single bad
    // file cannot kill collectors (dashboard, service, sync all read these).
    private fun Flow<Int>.catchInt(fallback: Int): Flow<Int> = catch { e ->
        if (e is CancellationException) throw e
        Log.w("SettingsRepo", "settings flow failed; using fallback $fallback", e)
        emit(fallback)
    }

    private fun Flow<Long>.catchLong(fallback: Long): Flow<Long> = catch { e ->
        if (e is CancellationException) throw e
        Log.w("SettingsRepo", "settings flow failed; using fallback $fallback", e)
        emit(fallback)
    }

    private fun Flow<String>.catchString(fallback: String): Flow<String> = catch { e ->
        if (e is CancellationException) throw e
        Log.w("SettingsRepo", "settings flow failed; using fallback", e)
        emit(fallback)
    }

    private fun Flow<Boolean>.catchBoolean(fallback: Boolean): Flow<Boolean> = catch { e ->
        if (e is CancellationException) throw e
        Log.w("SettingsRepo", "settings flow failed; using fallback $fallback", e)
        emit(fallback)
    }

    val workRatioFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.WORK_RATIO] ?: 4
    }.catchInt(4)

    val taskBonusFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TASK_COMPLETION_BONUS] ?: 5
    }.catchInt(5)

    /** Daily focus goal (minutes) driving the dashboard Focus ring. */
    val focusGoalMinutesFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.FOCUS_GOAL_MINUTES] ?: DEFAULT_FOCUS_GOAL_MINUTES)
            .coerceIn(MIN_FOCUS_GOAL_MINUTES, MAX_FOCUS_GOAL_MINUTES)
    }.catchInt(DEFAULT_FOCUS_GOAL_MINUTES)

    /** Daily completed-tasks goal driving the dashboard Tasks ring. */
    val dailyTasksGoalFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.DAILY_TASKS_GOAL] ?: DEFAULT_DAILY_TASKS_GOAL)
            .coerceIn(MIN_DAILY_TASKS_GOAL, MAX_DAILY_TASKS_GOAL)
    }.catchInt(DEFAULT_DAILY_TASKS_GOAL)

    val tickTickTokenFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_ACCESS_TOKEN] ?: ""
    }.catchString("")

    val tickTickRefreshTokenFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_REFRESH_TOKEN] ?: ""
    }.catchString("")

    /** Epoch millis when the OAuth access token expires. 0/negative = unknown (personal token or legacy). */
    val tickTickTokenExpiryFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_TOKEN_EXPIRES_AT] ?: 0L
    }.catchLong(0L)

    val tickTickClientIdFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_CLIENT_ID] ?: ""
    }.catchString("")

    val tickTickClientSecretFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_CLIENT_SECRET] ?: ""
    }.catchString("")

    val tickTickUserNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_USER_NAME] ?: ""
    }.catchString("")

    val tickTickNotificationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_NOTIFICATION_ENABLED] ?: true
    }.catchBoolean(true)

    /** Daily "log your focus work" reminder toggle. Off by default. */
    val dailyReminderEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DAILY_REMINDER_ENABLED] ?: false
    }.catchBoolean(false)

    /** Local wall-clock minute-of-day (0..1439) for the daily reminder. Defaults to 09:00. */
    val dailyReminderMinuteOfDayFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.DAILY_REMINDER_MINUTE_OF_DAY] ?: DEFAULT_DAILY_REMINDER_MINUTE_OF_DAY)
            .coerceIn(0, 1439)
    }.catchInt(DEFAULT_DAILY_REMINDER_MINUTE_OF_DAY)

    /** Canonical lockdown flag. Reads `lockdown_mode`, falling back to legacy `strict_mode` pre-migration. */
    val lockdownModeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            if (preferences.contains(PreferencesKeys.LOCKDOWN_MODE)) {
                preferences[PreferencesKeys.LOCKDOWN_MODE] ?: false
            } else {
                preferences[PreferencesKeys.STRICT_MODE] ?: false
            }
        }
        .onEach { enabled ->
            _lockdownMode.value = enabled
            lockdownModeLoaded.value = true
        }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w("SettingsRepo", "lockdownModeFlow failed; using false", e)
            _lockdownMode.value = false
            lockdownModeLoaded.value = true
            emit(false)
        }

    @Deprecated("Use lockdownModeFlow", ReplaceWith("lockdownModeFlow"))
    val strictModeFlow: Flow<Boolean> get() = lockdownModeFlow

    /** Cached lockdown flag; falls back to DataStore only until the cache is warm. */
    suspend fun isLockdownModeEnabled(): Boolean =
        if (lockdownModeLoaded.value) _lockdownMode.value else lockdownModeFlow.first()

    /** Non-suspending lockdown read for already-warm hot paths. */
    fun isLockdownNow(): Boolean = _lockdownMode.value

    /** When true, Boundaries UI must not allow removing blocked apps/websites. No cooldown. */
    val boundariesLockFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.BOUNDARIES_LOCK] ?: false
    }.catchBoolean(false)

    val nukeActiveFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.NUKE_ACTIVE] ?: false }
        .onEach { active ->
            _nukeActive.value = active
            nukeActiveLoaded.value = true
        }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w("SettingsRepo", "nukeActiveFlow failed; using false", e)
            _nukeActive.value = false
            nukeActiveLoaded.value = true
            emit(false)
        }

    val nukeStartedAtFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.NUKE_STARTED_AT] ?: 0L
    }.catchLong(0L)

    val nukeMeditationDoneAtFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.NUKE_MEDITATION_DONE_AT] ?: 0L
    }.catchLong(0L)

    /** Cached nuke flag; falls back to DataStore only until the cache is warm. */
    suspend fun isNukeActive(): Boolean =
        if (nukeActiveLoaded.value) _nukeActive.value else nukeActiveFlow.first()

    /** Non-suspending nuke read for already-warm hot paths. */
    fun isNukeActiveNow(): Boolean = _nukeActive.value

    // App Operations
    /** Cached blocked-app list; falls back to DataStore only until the cache is warm. */
    suspend fun getBlockedApps(): List<BlockedApp> =
        if (blockedAppsLoaded.value) _blockedApps.value else blockedAppsFlow.first()

    suspend fun updateBlockedApps(apps: List<BlockedApp>, markLocalChange: Boolean = true) {
        val committed = editSettings { preferences ->
            preferences[PreferencesKeys.BLOCKED_APPS_JSON] = json.encodeToString(apps)
            if (markLocalChange) preferences[PreferencesKeys.BLOCKED_APPS_UPDATED_AT] = System.currentTimeMillis()
        }
        if (committed) {
            _blockedApps.value = apps
            blockedAppsLoaded.value = true
        }
    }

    suspend fun getBlockedAppsUpdatedAt(): Long =
        readSettingsPrefs()[PreferencesKeys.BLOCKED_APPS_UPDATED_AT] ?: 0L

    /**
     * Atomic read-modify-write helpers (race fix, item 6): decode → mutate → encode
     * all run inside the single DataStore edit transform, so concurrent setters can
     * no longer drop each other's changes (the old read-outside-edit-then-write lost
     * updates whenever two writes interleaved). [mutate] returns false when nothing
     * changed, skipping the write entirely. Mirror caches refresh only after the
     * edit committed. Returns the merged list, or null when the edit failed.
     */
    private suspend fun editAppsAtomically(
        markLocalChange: Boolean = true,
        updatedAt: Long? = null,
        mutate: (MutableList<BlockedApp>) -> Boolean,
    ): List<BlockedApp>? {
        var result: List<BlockedApp>? = null
        val committed = editSettings { preferences ->
            val current = decodeBlockedApps(preferences[PreferencesKeys.BLOCKED_APPS_JSON]).toMutableList()
            if (mutate(current)) {
                preferences[PreferencesKeys.BLOCKED_APPS_JSON] = json.encodeToString(current)
                if (updatedAt != null) {
                    preferences[PreferencesKeys.BLOCKED_APPS_UPDATED_AT] = updatedAt
                } else if (markLocalChange) {
                    preferences[PreferencesKeys.BLOCKED_APPS_UPDATED_AT] = System.currentTimeMillis()
                }
                result = current
            }
        }
        if (!committed) return null
        result?.let { apps ->
            _blockedApps.value = apps
            blockedAppsLoaded.value = true
        }
        return result
    }

    private suspend fun editWebsitesAtomically(
        markLocalChange: Boolean = true,
        updatedAt: Long? = null,
        mutate: (MutableList<BlockedWebsite>) -> Boolean,
    ): List<BlockedWebsite>? {
        var result: List<BlockedWebsite>? = null
        val committed = editSettings { preferences ->
            val current = decodeBlockedWebsites(preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON]).toMutableList()
            if (mutate(current)) {
                preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON] = json.encodeToString(current)
                if (updatedAt != null) {
                    preferences[PreferencesKeys.BLOCKED_WEBSITES_UPDATED_AT] = updatedAt
                } else if (markLocalChange) {
                    preferences[PreferencesKeys.BLOCKED_WEBSITES_UPDATED_AT] = System.currentTimeMillis()
                }
                result = current
            }
        }
        if (!committed) return null
        result?.let { websites ->
            _blockedWebsites.value = websites
            blockedWebsitesLoaded.value = true
        }
        return result
    }

    suspend fun applyRemoteBlockedApps(apps: List<BlockedApp>, updatedAt: Long) {
        // Remote clients never send isPermanent; keep the local flag for matching packages
        // so a sync can't wipe a permanent block. Unmatched entries stay non-permanent.
        // The local list is read INSIDE the edit transform so the merge basis is atomic
        // with the write (race fix, item 6). Mirror cache updates after the commit.
        var merged: List<BlockedApp>? = null
        editSettings { preferences ->
            val localPermanentByPkg = decodeBlockedApps(preferences[PreferencesKeys.BLOCKED_APPS_JSON])
                .associateBy { it.packageName }
            val mergedList = apps.map { remote ->
                val local = localPermanentByPkg[remote.packageName]
                if (local != null) remote.copy(isPermanent = local.isPermanent)
                else remote.copy(isPermanent = false)
            }
            preferences[PreferencesKeys.BLOCKED_APPS_JSON] = json.encodeToString(mergedList)
            preferences[PreferencesKeys.BLOCKED_APPS_UPDATED_AT] = updatedAt
            merged = mergedList
        }
        merged?.let {
            _blockedApps.value = it
            blockedAppsLoaded.value = true
        }
    }

    suspend fun setAppBlocked(packageName: String, blocked: Boolean) {
        editAppsAtomically { current ->
            val index = current.indexOfFirst { it.packageName == packageName }
            if (index != -1) {
                current[index] = current[index].copy(isBlocked = blocked)
            } else {
                current.add(BlockedApp(packageName = packageName, appName = packageName, isBlocked = blocked))
            }
            true
        }
    }

    suspend fun setAppBlockedFull(packageName: String, appName: String, category: String, blocked: Boolean) {
        editAppsAtomically { current ->
            val index = current.indexOfFirst { it.packageName == packageName }
            if (index != -1) {
                current[index] = current[index].copy(appName = appName, category = category, isBlocked = blocked)
            } else {
                current.add(BlockedApp(packageName = packageName, appName = appName, category = category, isBlocked = blocked))
            }
            true
        }
    }

    /** Single JSON rewrite for bulk "Block All" actions — avoids N sequential DataStore edits. */
    data class AppBlockUpdate(
        val packageName: String,
        val appName: String,
        val category: String,
        val isBlocked: Boolean
    )

    suspend fun setAppsBlockedFullBatch(updates: List<AppBlockUpdate>) {
        if (updates.isEmpty()) return
        editAppsAtomically { current ->
            val indexByPkg = current.mapIndexed { i, app -> app.packageName to i }.toMap().toMutableMap()
            for (u in updates) {
                val index = indexByPkg[u.packageName]
                if (index != null) {
                    current[index] = current[index].copy(
                        appName = u.appName, category = u.category, isBlocked = u.isBlocked
                    )
                } else {
                    current.add(
                        BlockedApp(
                            packageName = u.packageName,
                            appName = u.appName,
                            category = u.category,
                            isBlocked = u.isBlocked
                        )
                    )
                    indexByPkg[u.packageName] = current.size - 1
                }
            }
            true
        }
    }

    /** Single JSON rewrite for bulk blocked-flag flips when metadata is already stored. */
    suspend fun setAppsBlockedBatch(states: Map<String, Boolean>) {
        if (states.isEmpty()) return
        editAppsAtomically { current ->
            var changed = false
            for (i in current.indices) {
                val next = states[current[i].packageName]
                if (next != null && current[i].isBlocked != next) {
                    current[i] = current[i].copy(isBlocked = next)
                    changed = true
                }
            }
            // Add unknown packages as blocked entries so bulk-block never silently drops.
            for ((pkg, blocked) in states) {
                if (current.none { it.packageName == pkg }) {
                    current.add(BlockedApp(packageName = pkg, appName = pkg, isBlocked = blocked))
                    changed = true
                }
            }
            changed
        }
    }

    /** Single JSON rewrite for bulk website block/unblock presets. */
    suspend fun setWebsitesBlockedBatch(states: Map<String, Boolean>) {
        if (states.isEmpty()) return
        editWebsitesAtomically { current ->
            var changed = false
            for (i in current.indices) {
                val key = current[i].domain.lowercase()
                val next = states[key] ?: states[current[i].domain]
                if (next != null && current[i].isBlocked != next) {
                    current[i] = current[i].copy(isBlocked = next)
                    changed = true
                }
            }
            changed
        }
    }

    suspend fun isAppBlocked(packageName: String): Boolean =
        getBlockedApps().any { it.packageName == packageName && it.isBlocked }

    /**
     * Toggles a permanent block for [packageName]. Enabling always blocks the app;
     * disabling clears the permanent flag but leaves the app blocked.
     */
    suspend fun setAppPermanent(packageName: String, permanent: Boolean) {
        editAppsAtomically { current ->
            val index = current.indexOfFirst { it.packageName == packageName }
            if (index != -1) {
                current[index] = current[index].copy(isBlocked = true, isPermanent = permanent)
                true
            } else if (permanent) {
                current.add(
                    BlockedApp(
                        packageName = packageName,
                        appName = packageName,
                        isBlocked = true,
                        isPermanent = true
                    )
                )
                true
            } else {
                // Disabling permanence for an unknown app is a no-op (unchanged list).
                false
            }
        }
    }

    /** Hot-path read for permanent enforcement; uses the in-memory cache when warm. */
    suspend fun isAppPermanent(packageName: String): Boolean {
        val apps = if (blockedAppsLoaded.value) _blockedApps.value else blockedAppsFlow.first()
        return apps.any { it.packageName == packageName && it.isPermanent }
    }

    // Website Operations
    /** Cached blocked-website list; falls back to DataStore only until the cache is warm. */
    suspend fun getBlockedWebsites(): List<BlockedWebsite> =
        if (blockedWebsitesLoaded.value) _blockedWebsites.value else blockedWebsitesFlow.first()

    suspend fun updateBlockedWebsites(websites: List<BlockedWebsite>, markLocalChange: Boolean = true) {
        val committed = editSettings { preferences ->
            preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON] = json.encodeToString(websites)
            if (markLocalChange) preferences[PreferencesKeys.BLOCKED_WEBSITES_UPDATED_AT] = System.currentTimeMillis()
        }
        if (committed) {
            _blockedWebsites.value = websites
            blockedWebsitesLoaded.value = true
        }
    }

    suspend fun getBlockedWebsitesUpdatedAt(): Long =
        readSettingsPrefs()[PreferencesKeys.BLOCKED_WEBSITES_UPDATED_AT] ?: 0L

    suspend fun applyRemoteBlockedWebsites(websites: List<BlockedWebsite>, updatedAt: Long) {
        // Remote clients never send isPermanent; keep the local flag for matching domains
        // so a sync can't wipe a permanent block. Unmatched entries stay non-permanent.
        // The local list is read INSIDE the edit transform (atomic merge basis, item 6).
        var merged: List<BlockedWebsite>? = null
        editSettings { preferences ->
            val localPermanentByDomain = decodeBlockedWebsites(preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON])
                .associateBy { it.domain.lowercase() }
            val mergedList = websites.map { remote ->
                val local = localPermanentByDomain[remote.domain.lowercase()]
                if (local != null) remote.copy(isPermanent = local.isPermanent)
                else remote.copy(isPermanent = false)
            }
            preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON] = json.encodeToString(mergedList)
            preferences[PreferencesKeys.BLOCKED_WEBSITES_UPDATED_AT] = updatedAt
            merged = mergedList
        }
        merged?.let {
            _blockedWebsites.value = it
            blockedWebsitesLoaded.value = true
        }
    }

    suspend fun setWebsiteBlocked(domain: String, blocked: Boolean) {
        editWebsitesAtomically { current ->
            val index = current.indexOfFirst { it.domain.equals(domain, ignoreCase = true) }
            if (index != -1) {
                current[index] = current[index].copy(isBlocked = blocked)
            } else {
                current.add(BlockedWebsite(domain = domain.lowercase(), displayName = domain, isBlocked = blocked, isCustom = true))
            }
            true
        }
    }

    suspend fun addCustomWebsite(domain: String): Boolean {
        val cleaned = cleanDomain(domain)
        if (cleaned.isBlank()) return false
        var added = false
        val committed = editWebsitesAtomically { current ->
            if (current.any { it.domain.equals(cleaned, ignoreCase = true) }) {
                false // already present
            } else {
                current.add(0, BlockedWebsite(domain = cleaned, displayName = cleaned, isBlocked = true, category = "Custom", isCustom = true))
                added = true
                true
            }
        }
        // Report success only when the addition actually persisted.
        return committed != null && added
    }

    suspend fun removeCustomWebsite(domain: String) {
        editWebsitesAtomically { current ->
            var changed = false
            val kept = current.filterNot { it.domain.equals(domain, ignoreCase = true) && it.isCustom }
            if (kept.size != current.size) {
                current.clear()
                current.addAll(kept)
                changed = true
            }
            changed
        }
    }

    suspend fun isWebsiteBlocked(urlOrDomain: String): Boolean {
        val normalized = cleanDomain(urlOrDomain)
        if (normalized.isBlank()) return false
        val websites = getBlockedWebsites()
        return websites.any { site ->
            site.isBlocked && (
                normalized.equals(site.domain, ignoreCase = true) ||
                normalized.endsWith(".${site.domain}", ignoreCase = true)
            )
        }
    }

    /**
     * Toggles a permanent block for [domain]. Enabling always blocks the site;
     * disabling clears the permanent flag but leaves the site blocked.
     */
    suspend fun setWebsitePermanent(domain: String, permanent: Boolean) {
        val cleaned = cleanDomain(domain)
        if (cleaned.isBlank()) return
        editWebsitesAtomically { current ->
            val index = current.indexOfFirst { it.domain.equals(cleaned, ignoreCase = true) }
            if (index != -1) {
                current[index] = current[index].copy(isBlocked = true, isPermanent = permanent)
                true
            } else if (permanent) {
                current.add(
                    BlockedWebsite(
                        domain = cleaned,
                        displayName = cleaned,
                        isBlocked = true,
                        isCustom = true,
                        isPermanent = true
                    )
                )
                true
            } else {
                // Disabling permanence for an unknown domain is a no-op.
                false
            }
        }
    }

    /** Hot-path read for permanent enforcement; uses the in-memory cache when warm. */
    suspend fun isWebsitePermanent(urlOrDomain: String): Boolean {
        val normalized = cleanDomain(urlOrDomain)
        if (normalized.isBlank()) return false
        val websites = if (blockedWebsitesLoaded.value) _blockedWebsites.value else blockedWebsitesFlow.first()
        return websites.any { site ->
            site.isPermanent && (
                normalized.equals(site.domain, ignoreCase = true) ||
                normalized.endsWith(".${site.domain}", ignoreCase = true)
            )
        }
    }

    // Settings Operations
    suspend fun setWorkRatio(ratio: Int) {
        editSettings { preferences ->
            preferences[PreferencesKeys.WORK_RATIO] = ratio.coerceIn(1, 20)
            preferences[PreferencesKeys.WORK_RATIO_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setTaskBonus(minutes: Int) {
        editSettings { preferences ->
            preferences[PreferencesKeys.TASK_COMPLETION_BONUS] = minutes.coerceIn(0, 60)
            preferences[PreferencesKeys.TASK_COMPLETION_BONUS_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    /** Current work-to-scroll ratio (minutes of work per minute of scroll). */
    suspend fun getWorkRatio(): Int = workRatioFlow.first()

    /** Current completed-task bonus minutes. */
    suspend fun getTaskBonus(): Int = taskBonusFlow.first()

    /** Last-writer-wins timestamp for Convex userPrefs.workRatio sync. */
    suspend fun getWorkRatioUpdatedAt(): Long =
        readSettingsPrefs()[PreferencesKeys.WORK_RATIO_UPDATED_AT] ?: 0L

    /** Last-writer-wins timestamp for Convex userPrefs.taskBonusMinutes sync. */
    suspend fun getTaskBonusUpdatedAt(): Long =
        readSettingsPrefs()[PreferencesKeys.TASK_COMPLETION_BONUS_UPDATED_AT] ?: 0L

    /**
     * Last-writer-wins apply from Convex userPrefs (see FocusSyncManager prefs step).
     * Only writes when the remote timestamp is newer than the locally stored one.
     */
    suspend fun applyRemoteWorkRatio(ratio: Int, updatedAt: Long) {
        editSettings { preferences ->
            val current = preferences[PreferencesKeys.WORK_RATIO_UPDATED_AT] ?: 0L
            if (updatedAt > current) {
                preferences[PreferencesKeys.WORK_RATIO] = ratio.coerceIn(1, 20)
                preferences[PreferencesKeys.WORK_RATIO_UPDATED_AT] = updatedAt
            }
        }
    }

    /**
     * Last-writer-wins apply from Convex userPrefs (see FocusSyncManager prefs step).
     * Only writes when the remote timestamp is newer than the locally stored one.
     */
    suspend fun applyRemoteTaskBonus(minutes: Int, updatedAt: Long) {
        editSettings { preferences ->
            val current = preferences[PreferencesKeys.TASK_COMPLETION_BONUS_UPDATED_AT] ?: 0L
            if (updatedAt > current) {
                preferences[PreferencesKeys.TASK_COMPLETION_BONUS] = minutes.coerceIn(0, 60)
                preferences[PreferencesKeys.TASK_COMPLETION_BONUS_UPDATED_AT] = updatedAt
            }
        }
    }

    suspend fun setFocusGoalMinutes(minutes: Int) {
        editSettings { preferences ->
            preferences[PreferencesKeys.FOCUS_GOAL_MINUTES] =
                minutes.coerceIn(MIN_FOCUS_GOAL_MINUTES, MAX_FOCUS_GOAL_MINUTES)
        }
    }

    suspend fun setDailyTasksGoal(tasks: Int) {
        editSettings { preferences ->
            preferences[PreferencesKeys.DAILY_TASKS_GOAL] =
                tasks.coerceIn(MIN_DAILY_TASKS_GOAL, MAX_DAILY_TASKS_GOAL)
        }
    }

    // TickTick OAuth Operations
    suspend fun beginTickTickLogin(): String {
        val state = java.util.UUID.randomUUID().toString()
        editSettings {
            it[PreferencesKeys.OAUTH_STATE] = state
            it[PreferencesKeys.OAUTH_STARTED_AT] = System.currentTimeMillis()
        }
        return state
    }

    suspend fun consumeTickTickState(state: String?): Boolean {
        var valid = false
        editSettings {
            valid = com.focuslock.app.service.OAuthStateValidator.isValid(
                it[PreferencesKeys.OAUTH_STATE], state,
                it[PreferencesKeys.OAUTH_STARTED_AT] ?: 0L, System.currentTimeMillis()
            )
            if (valid) {
                it.remove(PreferencesKeys.OAUTH_STATE)
                it.remove(PreferencesKeys.OAUTH_STARTED_AT)
            }
        }
        return valid
    }

    /**
     * Consumes a pending login for the manual bare-code path (user pasted only the code,
     * no state). Proves the login was initiated on this device within the last 10 minutes.
     * Prefer [consumeTickTickState] when the full redirect URL (with state) was pasted.
     */
    suspend fun consumePendingTickTickLogin(): Boolean {
        var valid = false
        editSettings {
            val state = it[PreferencesKeys.OAUTH_STATE]
            val startedAt = it[PreferencesKeys.OAUTH_STARTED_AT] ?: 0L
            val now = System.currentTimeMillis()
            valid = !state.isNullOrBlank() && startedAt > 0 && now - startedAt in 0..600_000L
            if (valid) {
                it.remove(PreferencesKeys.OAUTH_STATE)
                it.remove(PreferencesKeys.OAUTH_STARTED_AT)
            }
        }
        return valid
    }

    suspend fun setTickTickOAuthCredentials(clientId: String, clientSecret: String) {
        editSettings { preferences ->
            preferences[PreferencesKeys.TICKTICK_CLIENT_ID] = clientId.trim()
            preferences[PreferencesKeys.TICKTICK_CLIENT_SECRET] = clientSecret.trim()
        }
    }

    suspend fun setTickTickAuthSuccess(
        token: String,
        userName: String = "",
        refreshToken: String? = null,
        expiresInSec: Long? = null
    ) {
        editSettings { preferences ->
            preferences[PreferencesKeys.TICKTICK_ACCESS_TOKEN] = token.trim()
            if (userName.isNotBlank()) {
                preferences[PreferencesKeys.TICKTICK_USER_NAME] = userName.trim()
            }
            if (refreshToken != null) {
                if (refreshToken.isBlank()) {
                    preferences.remove(PreferencesKeys.TICKTICK_REFRESH_TOKEN)
                } else {
                    preferences[PreferencesKeys.TICKTICK_REFRESH_TOKEN] = refreshToken.trim()
                }
            } else {
                // Personal-token path passes null: clear any stale OAuth refresh state.
                preferences.remove(PreferencesKeys.TICKTICK_REFRESH_TOKEN)
            }
            if (expiresInSec != null && expiresInSec > 0) {
                preferences[PreferencesKeys.TICKTICK_TOKEN_EXPIRES_AT] =
                    System.currentTimeMillis() + expiresInSec * 1000L
            } else {
                preferences.remove(PreferencesKeys.TICKTICK_TOKEN_EXPIRES_AT)
            }
        }
    }

    suspend fun clearTickTickAuth() {
        editSettings { preferences ->
            preferences[PreferencesKeys.TICKTICK_ACCESS_TOKEN] = ""
            preferences[PreferencesKeys.TICKTICK_USER_NAME] = ""
            preferences.remove(PreferencesKeys.TICKTICK_REFRESH_TOKEN)
            preferences.remove(PreferencesKeys.TICKTICK_TOKEN_EXPIRES_AT)
        }
    }

    suspend fun setTickTickNotificationEnabled(enabled: Boolean) {
        editSettings { preferences ->
            preferences[PreferencesKeys.TICKTICK_NOTIFICATION_ENABLED] = enabled
        }
    }

    suspend fun setDailyReminderEnabled(enabled: Boolean) {
        editSettings { preferences ->
            preferences[PreferencesKeys.DAILY_REMINDER_ENABLED] = enabled
        }
    }

    suspend fun setDailyReminderMinuteOfDay(minuteOfDay: Int) {
        editSettings { preferences ->
            preferences[PreferencesKeys.DAILY_REMINDER_MINUTE_OF_DAY] = minuteOfDay.coerceIn(0, 1439)
        }
    }

    suspend fun setLockdownMode(enabled: Boolean) {
        val now = System.currentTimeMillis()
        // Opportunistic migration so legacy timestamps survive the rename.
        ensureLockdownMigrated()
        val committed = editSettings { preferences ->
            preferences[PreferencesKeys.LOCKDOWN_MODE] = enabled
            preferences[PreferencesKeys.LOCKDOWN_MODE_UPDATED_AT] = now
            if (enabled) {
                preferences[PreferencesKeys.LOCKDOWN_MODE_ENABLED_AT] = now
            }
            // NOTE: LOCKDOWN_MODE_ENABLED_AT is intentionally kept on disable so the
            // cooldown window stays auditable (and re-enabling restarts it).
        }
        if (committed) {
            _lockdownMode.value = enabled
            lockdownModeLoaded.value = true
        }
    }

    /** One-time migration: if lockdown_mode missing but strict_mode present, copy value+timestamps. */
    suspend fun ensureLockdownMigrated() {
        if (!lockdownMigrationChecked.compareAndSet(false, true)) return
        // Never rethrows (crash fix): a failed migration is retried on the next call;
        // callers (UI writes, sync reads) must not die on a corrupt file.
        try {
            editSettings { preferences ->
                if (!preferences.contains(PreferencesKeys.LOCKDOWN_MODE) &&
                    preferences.contains(PreferencesKeys.STRICT_MODE)
                ) {
                    preferences[PreferencesKeys.LOCKDOWN_MODE] =
                        preferences[PreferencesKeys.STRICT_MODE] ?: false
                    preferences[PreferencesKeys.LOCKDOWN_MODE_UPDATED_AT] =
                        preferences[PreferencesKeys.STRICT_MODE_UPDATED_AT] ?: 0L
                    preferences[PreferencesKeys.LOCKDOWN_MODE_ENABLED_AT] =
                        preferences[PreferencesKeys.STRICT_MODE_ENABLED_AT] ?: 0L
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lockdownMigrationChecked.set(false) // allow retry on transient failure
            Log.w("SettingsRepo", "lockdown migration failed; will retry", e)
        }
    }

    /** Epoch-millis when Lockdown Mode was last enabled. 0 = never enabled. */
    suspend fun getLockdownModeEnabledAt(): Long {
        ensureLockdownMigrated()
        return readSettingsPrefs()[PreferencesKeys.LOCKDOWN_MODE_ENABLED_AT] ?: 0L
    }

    /** Last-writer-wins timestamp for Convex userPrefs.lockdownMode sync. */
    suspend fun getLockdownModeUpdatedAt(): Long {
        ensureLockdownMigrated()
        return readSettingsPrefs()[PreferencesKeys.LOCKDOWN_MODE_UPDATED_AT] ?: 0L
    }

    /** True once the disable-cooldown after enabling has elapsed (or never enabled). */
    suspend fun canDisableLockdownMode(): Boolean = lockdownCooldownRemainingMs() <= 0L

    /** Millis remaining before Lockdown Mode may be turned off again. 0 = may disable now. */
    suspend fun lockdownCooldownRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val enabledAt = getLockdownModeEnabledAt()
        if (enabledAt <= 0L) return 0L
        return (enabledAt + LOCKDOWN_DISABLE_COOLDOWN_MS - now).coerceAtLeast(0L)
    }

    /**
     * Last-writer-wins apply from Convex userPrefs (see FocusSyncManager prefs step).
     * Cooldown anchor (ENABLED_AT) is only set when enabling and never cleared, so a
     * remote disable can't be abused to dodge the local 24h lock.
     */
    suspend fun applyRemoteLockdown(enabled: Boolean, updatedAt: Long, enabledAt: Long = updatedAt) {
        ensureLockdownMigrated()
        val committed = editSettings { preferences ->
            preferences[PreferencesKeys.LOCKDOWN_MODE] = enabled
            preferences[PreferencesKeys.LOCKDOWN_MODE_UPDATED_AT] = updatedAt
            if (enabled) {
                val current = preferences[PreferencesKeys.LOCKDOWN_MODE_ENABLED_AT] ?: 0L
                if (current <= 0L) preferences[PreferencesKeys.LOCKDOWN_MODE_ENABLED_AT] = enabledAt
            }
        }
        if (committed) {
            _lockdownMode.value = enabled
            lockdownModeLoaded.value = true
        }
    }

    /** Boundaries lock: no cooldown, just stamp updated_at. */
    suspend fun setBoundariesLock(locked: Boolean) {
        editSettings { preferences ->
            preferences[PreferencesKeys.BOUNDARIES_LOCK] = locked
            preferences[PreferencesKeys.BOUNDARIES_LOCK_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    /** Last-writer-wins timestamp for boundaries_lock sync. */
    suspend fun getBoundariesLockUpdatedAt(): Long =
        readSettingsPrefs()[PreferencesKeys.BOUNDARIES_LOCK_UPDATED_AT] ?: 0L

    @Deprecated("Use setLockdownMode", ReplaceWith("setLockdownMode(enabled)"))
    suspend fun setStrictMode(enabled: Boolean) = setLockdownMode(enabled)

    /** Epoch-millis when Strict Mode was last enabled. 0 = never enabled. */
    @Deprecated("Use getLockdownModeEnabledAt", ReplaceWith("getLockdownModeEnabledAt()"))
    suspend fun getStrictModeEnabledAt(): Long = getLockdownModeEnabledAt()

    /** Last-writer-wins timestamp for Convex userPrefs.strictMode sync. */
    @Deprecated("Use getLockdownModeUpdatedAt", ReplaceWith("getLockdownModeUpdatedAt()"))
    suspend fun getStrictModeUpdatedAt(): Long = getLockdownModeUpdatedAt()

    /** True once the disable-cooldown after enabling has elapsed (or never enabled). */
    @Deprecated("Use canDisableLockdownMode", ReplaceWith("canDisableLockdownMode()"))
    suspend fun canDisableStrictMode(): Boolean = canDisableLockdownMode()

    /** Millis remaining before Strict Mode may be turned off again. 0 = may disable now. */
    @Deprecated("Use lockdownCooldownRemainingMs", ReplaceWith("lockdownCooldownRemainingMs(now)"))
    suspend fun strictModeCooldownRemainingMs(now: Long = System.currentTimeMillis()): Long =
        lockdownCooldownRemainingMs(now)

    /**
     * Last-writer-wins apply from Convex userPrefs (see FocusSyncManager prefs step).
     * Cooldown anchor (ENABLED_AT) is only set when enabling and never cleared, so a
     * remote disable can't be abused to dodge the local 24h lock.
     */
    @Deprecated("Use applyRemoteLockdown", ReplaceWith("applyRemoteLockdown(enabled, updatedAt, enabledAt)"))
    suspend fun applyRemoteStrictMode(enabled: Boolean, updatedAt: Long, enabledAt: Long = updatedAt) =
        applyRemoteLockdown(enabled, updatedAt, enabledAt)

    suspend fun setNukeActive(active: Boolean, startedAt: Long = System.currentTimeMillis()) {
        val committed = editSettings { preferences ->
            preferences[PreferencesKeys.NUKE_ACTIVE] = active
            if (active) {
                preferences[PreferencesKeys.NUKE_STARTED_AT] = startedAt
                preferences.remove(PreferencesKeys.NUKE_MEDITATION_DONE_AT)
            }
        }
        if (committed) {
            _nukeActive.value = active
            nukeActiveLoaded.value = true
        }
    }

    suspend fun setNukeMeditationDone(doneAt: Long = System.currentTimeMillis()) {
        editSettings { preferences ->
            preferences[PreferencesKeys.NUKE_MEDITATION_DONE_AT] = doneAt
        }
    }

    suspend fun clearNuke() {
        val committed = editSettings { preferences ->
            preferences[PreferencesKeys.NUKE_ACTIVE] = false
            preferences.remove(PreferencesKeys.NUKE_MEDITATION_DONE_AT)
        }
        if (committed) {
            _nukeActive.value = false
            nukeActiveLoaded.value = true
        }
    }

    companion object {
        /** Cooldown before Lockdown Mode can be turned off again once enabled. */
        const val LOCKDOWN_DISABLE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

        /** Cooldown before Strict Mode can be turned off again once enabled. */
        @Deprecated("Use LOCKDOWN_DISABLE_COOLDOWN_MS")
        const val STRICT_MODE_DISABLE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

        const val DEFAULT_FOCUS_GOAL_MINUTES = 120
        const val DEFAULT_DAILY_TASKS_GOAL = 7
        const val MIN_FOCUS_GOAL_MINUTES = 15
        const val MAX_FOCUS_GOAL_MINUTES = 720
        const val MIN_DAILY_TASKS_GOAL = 1
        const val MAX_DAILY_TASKS_GOAL = 50

        /** Default daily reminder time: 09:00 local. */
        const val DEFAULT_DAILY_REMINDER_MINUTE_OF_DAY = 9 * 60

        fun cleanDomain(raw: String): String {
            val trimmed = raw.trim().lowercase()
            return try {
                val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    "https://$trimmed"
                } else {
                    trimmed
                }
                val uri = URI(withScheme)
                val host = uri.host ?: trimmed
                host.removePrefix("www.").removePrefix("m.")
            } catch (e: Exception) {
                trimmed.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.").substringBefore("/")
            }
        }
    }
}
