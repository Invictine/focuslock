package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

private val Context.dataStore by preferencesDataStore(name = "focuslock_settings")

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    object PreferencesKeys {
        val BLOCKED_APPS_JSON = stringPreferencesKey("blocked_apps_json")
        val BLOCKED_WEBSITES_JSON = stringPreferencesKey("blocked_websites_json")
        val WORK_RATIO = intPreferencesKey("work_ratio") // e.g. 4 => 4 min work = 1 min scroll
        val TASK_COMPLETION_BONUS = intPreferencesKey("task_completion_bonus")
        val STRICT_MODE = booleanPreferencesKey("strict_mode")
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
    }

    // Apps Flow
    val blockedAppsFlow: Flow<List<BlockedApp>> = context.dataStore.data.map { preferences ->
        val appsJson = preferences[PreferencesKeys.BLOCKED_APPS_JSON]
        if (appsJson.isNullOrBlank()) {
            BlockedApp.DEFAULT_DOOMSCROLL_APPS
        } else {
            try {
                json.decodeFromString(appsJson)
            } catch (e: Exception) {
                BlockedApp.DEFAULT_DOOMSCROLL_APPS
            }
        }
    }

    // Websites Flow
    val blockedWebsitesFlow: Flow<List<BlockedWebsite>> = context.dataStore.data.map { preferences ->
        val websitesJson = preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON]
        if (websitesJson.isNullOrBlank()) {
            BlockedWebsite.DEFAULT_BLOCKED_WEBSITES
        } else {
            try {
                json.decodeFromString(websitesJson)
            } catch (e: Exception) {
                BlockedWebsite.DEFAULT_BLOCKED_WEBSITES
            }
        }
    }

    val workRatioFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.WORK_RATIO] ?: 4
    }

    val taskBonusFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TASK_COMPLETION_BONUS] ?: 5
    }

    val tickTickTokenFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_ACCESS_TOKEN] ?: ""
    }

    val tickTickRefreshTokenFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_REFRESH_TOKEN] ?: ""
    }

    /** Epoch millis when the OAuth access token expires. 0/negative = unknown (personal token or legacy). */
    val tickTickTokenExpiryFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_TOKEN_EXPIRES_AT] ?: 0L
    }

    val tickTickClientIdFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_CLIENT_ID] ?: ""
    }

    val tickTickClientSecretFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_CLIENT_SECRET] ?: ""
    }

    val tickTickUserNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_USER_NAME] ?: ""
    }

    val tickTickNotificationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TICKTICK_NOTIFICATION_ENABLED] ?: true
    }

    val strictModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.STRICT_MODE] ?: false
    }

    val nukeActiveFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.NUKE_ACTIVE] ?: false
    }

    val nukeStartedAtFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.NUKE_STARTED_AT] ?: 0L
    }

    val nukeMeditationDoneAtFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.NUKE_MEDITATION_DONE_AT] ?: 0L
    }

    // App Operations
    suspend fun getBlockedApps(): List<BlockedApp> = blockedAppsFlow.first()

    suspend fun updateBlockedApps(apps: List<BlockedApp>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BLOCKED_APPS_JSON] = json.encodeToString(apps)
        }
    }

    suspend fun setAppBlocked(packageName: String, blocked: Boolean) {
        val current = getBlockedApps().toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            current[index] = current[index].copy(isBlocked = blocked)
        } else {
            current.add(BlockedApp(packageName = packageName, appName = packageName, isBlocked = blocked))
        }
        updateBlockedApps(current)
    }

    suspend fun setAppBlockedFull(packageName: String, appName: String, category: String, blocked: Boolean) {
        val current = getBlockedApps().toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            current[index] = current[index].copy(appName = appName, category = category, isBlocked = blocked)
        } else {
            current.add(BlockedApp(packageName = packageName, appName = appName, category = category, isBlocked = blocked))
        }
        updateBlockedApps(current)
    }

    suspend fun isAppBlocked(packageName: String): Boolean {
        val apps = getBlockedApps()
        return apps.any { it.packageName == packageName && it.isBlocked }
    }

    // Website Operations
    suspend fun getBlockedWebsites(): List<BlockedWebsite> = blockedWebsitesFlow.first()

    suspend fun updateBlockedWebsites(websites: List<BlockedWebsite>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BLOCKED_WEBSITES_JSON] = json.encodeToString(websites)
        }
    }

    suspend fun setWebsiteBlocked(domain: String, blocked: Boolean) {
        val current = getBlockedWebsites().toMutableList()
        val index = current.indexOfFirst { it.domain.equals(domain, ignoreCase = true) }
        if (index != -1) {
            current[index] = current[index].copy(isBlocked = blocked)
        } else {
            current.add(BlockedWebsite(domain = domain.lowercase(), displayName = domain, isBlocked = blocked, isCustom = true))
        }
        updateBlockedWebsites(current)
    }

    suspend fun addCustomWebsite(domain: String): Boolean {
        val cleaned = cleanDomain(domain)
        if (cleaned.isBlank()) return false
        val current = getBlockedWebsites().toMutableList()
        if (current.any { it.domain.equals(cleaned, ignoreCase = true) }) {
            return false
        }
        current.add(0, BlockedWebsite(domain = cleaned, displayName = cleaned, isBlocked = true, category = "Custom", isCustom = true))
        updateBlockedWebsites(current)
        return true
    }

    suspend fun removeCustomWebsite(domain: String) {
        val current = getBlockedWebsites().filterNot { it.domain.equals(domain, ignoreCase = true) && it.isCustom }
        updateBlockedWebsites(current)
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

    // Settings Operations
    suspend fun setWorkRatio(ratio: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WORK_RATIO] = ratio.coerceIn(1, 20)
        }
    }

    suspend fun setTaskBonus(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TASK_COMPLETION_BONUS] = minutes.coerceIn(0, 60)
        }
    }

    // TickTick OAuth Operations
    suspend fun beginTickTickLogin(): String {
        val state = java.util.UUID.randomUUID().toString()
        context.dataStore.edit {
            it[PreferencesKeys.OAUTH_STATE] = state
            it[PreferencesKeys.OAUTH_STARTED_AT] = System.currentTimeMillis()
        }
        return state
    }

    suspend fun consumeTickTickState(state: String?): Boolean {
        var valid = false
        context.dataStore.edit {
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
        context.dataStore.edit {
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
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TICKTICK_ACCESS_TOKEN] = ""
            preferences[PreferencesKeys.TICKTICK_USER_NAME] = ""
            preferences.remove(PreferencesKeys.TICKTICK_REFRESH_TOKEN)
            preferences.remove(PreferencesKeys.TICKTICK_TOKEN_EXPIRES_AT)
        }
    }

    suspend fun setTickTickNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TICKTICK_NOTIFICATION_ENABLED] = enabled
        }
    }

    suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STRICT_MODE] = enabled
        }
    }

    suspend fun setNukeActive(active: Boolean, startedAt: Long = System.currentTimeMillis()) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.NUKE_ACTIVE] = active
            if (active) {
                preferences[PreferencesKeys.NUKE_STARTED_AT] = startedAt
                preferences.remove(PreferencesKeys.NUKE_MEDITATION_DONE_AT)
            }
        }
    }

    suspend fun setNukeMeditationDone(doneAt: Long = System.currentTimeMillis()) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.NUKE_MEDITATION_DONE_AT] = doneAt
        }
    }

    suspend fun clearNuke() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.NUKE_ACTIVE] = false
            preferences.remove(PreferencesKeys.NUKE_MEDITATION_DONE_AT)
        }
    }

    companion object {
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
