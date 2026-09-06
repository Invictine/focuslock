package com.focuslock.app.service

import com.focuslock.app.BuildConfig
import com.focuslock.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Resolves TickTick OAuth credentials.
 *
 * Priority: a complete user-entered pair in Settings (advanced override),
 * then BuildConfig values from gitignored local.properties.
 */
object TickTickAuthConfig {

    val bakedClientId: String get() = BuildConfig.TICKTICK_CLIENT_ID.orEmpty().trim()
    val bakedClientSecret: String get() = BuildConfig.TICKTICK_CLIENT_SECRET.orEmpty().trim()

    val hasBakedCredentials: Boolean get() = bakedClientId.isNotBlank() && bakedClientSecret.isNotBlank()

    private suspend fun credentials(settings: SettingsRepository): Pair<String, String> {
        val id = settings.tickTickClientIdFlow.first().trim()
        val secret = settings.tickTickClientSecretFlow.first().trim()
        return if (id.isNotBlank() && secret.isNotBlank()) id to secret
        else bakedClientId to bakedClientSecret
    }

    suspend fun effectiveClientId(settings: SettingsRepository): String = credentials(settings).first
    suspend fun effectiveClientSecret(settings: SettingsRepository): String = credentials(settings).second

    suspend fun hasUsableCredentials(settings: SettingsRepository): Boolean {
        return effectiveClientId(settings).isNotBlank() && effectiveClientSecret(settings).isNotBlank()
    }

    /**
     * Returns a usable access token, refreshing OAuth tokens when expired.
     * Personal tokens (tp_...) have no expiry and are returned as-is.
     * Returns null when no token is stored or refresh failed (user must reconnect).
     */
    suspend fun getValidAccessToken(
        settings: SettingsRepository,
        api: TickTickApiClient = TickTickApiClient()
    ): String? {
        val token = settings.tickTickTokenFlow.first().trim()
        if (token.isBlank()) return null
        // Personal access tokens don't expire and have no refresh flow.
        if (token.startsWith("tp_")) return token

        val expiresAt = settings.tickTickTokenExpiryFlow.first()
        // No expiry stored (legacy) or still valid with 5-min skew: use as-is.
        if (expiresAt <= 0L || System.currentTimeMillis() < expiresAt - 5 * 60 * 1000L) {
            return token
        }

        val refreshToken = settings.tickTickRefreshTokenFlow.first().trim()
        if (refreshToken.isBlank()) return token // Can't refresh; try anyway, API will reject if dead.

        val clientId = effectiveClientId(settings)
        val clientSecret = effectiveClientSecret(settings)
        if (clientId.isBlank() || clientSecret.isBlank()) return token

        val refreshed = api.refreshAccessToken(clientId, clientSecret, refreshToken) ?: return null
        if (refreshed.accessToken.isBlank()) return null
        settings.setTickTickAuthSuccess(
            token = refreshed.accessToken,
            refreshToken = refreshed.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken,
            expiresInSec = refreshed.expiresIn
        )
        return refreshed.accessToken
    }
}
