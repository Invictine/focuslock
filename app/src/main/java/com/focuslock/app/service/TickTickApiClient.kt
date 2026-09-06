package com.focuslock.app.service

import android.net.Uri
import android.util.Log
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class TickTickProject(
    val id: String,
    val name: String,
    val color: String? = null,
    val closed: Boolean = false
)

@Serializable
data class TickTickTaskItem(
    val id: String,
    val projectId: String,
    val title: String,
    val status: Int = 0, // 0: Normal, 2: Completed
    val completedTime: String? = null,
    val startDate: String? = null,
    val dueDate: String? = null
)

@Serializable
data class TickTickProjectData(
    val project: TickTickProject? = null,
    val tasks: List<TickTickTaskItem> = emptyList()
)

@Serializable
data class TickTickOAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null
)

@Serializable
data class TickTickUserProfile(
    val username: String? = null,
    val email: String? = null,
    val nickname: String? = null
)

class TickTickApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val TAG = "TickTickApiClient"
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    companion object {
        const val OAUTH_AUTHORIZE_URL = "https://ticktick.com/oauth/authorize"
        const val OAUTH_TOKEN_URL = "https://ticktick.com/oauth/token"
        // TickTick's portal only accepts http(s) redirect URLs (custom schemes like
        // focuslock:// are rejected with "Please enter right url"), so use the
        // loopback URL TickTick itself defaults to. Must match the portal entry exactly.
        const val REDIRECT_URI = "http://127.0.0.1:8080/"
        const val LEGACY_REDIRECT_URI = "focuslock://oauth/callback"

        internal fun authorizationCodeRequest(clientId: String, clientSecret: String, code: String): Request =
            Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .header("Authorization", Credentials.basic(clientId, clientSecret))
                .post(FormBody.Builder()
                    .add("code", code)
                    .add("grant_type", "authorization_code")
                    .add("scope", "tasks:read tasks:write")
                    .add("redirect_uri", REDIRECT_URI)
                    .build())
                .build()

        fun buildAuthorizeUrl(clientId: String, state: String): String {
            val encodedClient = java.net.URLEncoder.encode(clientId, "UTF-8")
            val encodedRedirect = java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
            val encodedScope = java.net.URLEncoder.encode("tasks:read tasks:write", "UTF-8")
            val encodedState = java.net.URLEncoder.encode(state, "UTF-8")
            return "$OAUTH_AUTHORIZE_URL?client_id=$encodedClient&scope=$encodedScope&response_type=code&redirect_uri=$encodedRedirect&state=$encodedState"
        }

        /**
         * Parses what the user pastes back from the browser after TickTick redirects to
         * the loopback URL (which can't load on-device). Accepts either the full redirect
         * URL (preferred, preserves state) or a bare authorization code.
         * Returns Pair(code, state?) or null when nothing usable is found.
         */
        fun parseManualCallback(input: String): Pair<String, String?>? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val codeFromParam = Regex("[?&]code=([^&\\s#]+)").find(trimmed)?.groupValues?.get(1)
            if (codeFromParam != null) {
                val decodedCode = try {
                    java.net.URLDecoder.decode(codeFromParam, "UTF-8")
                } catch (_: Exception) {
                    codeFromParam
                }
                if (decodedCode.isBlank()) return null
                val stateFromParam = Regex("[?&]state=([^&\\s#]+)").find(trimmed)?.groupValues?.get(1)?.let {
                    try {
                        java.net.URLDecoder.decode(it, "UTF-8")
                    } catch (_: Exception) {
                        it
                    }
                }
                return decodedCode to stateFromParam
            }
            // Bare code: no spaces, no URL scheme.
            if (!trimmed.contains("\\s".toRegex()) && !trimmed.contains("://")) {
                return trimmed to null
            }
            return null
        }
    }

    suspend fun exchangeCodeForToken(
        clientId: String,
        clientSecret: String,
        code: String
    ): TickTickOAuthTokenResponse? = withContext(Dispatchers.IO) {
        try {
            val request = authorizationCodeRequest(clientId, clientSecret, code)

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    json.decodeFromString<TickTickOAuthTokenResponse>(body)
                } else {
                    Log.e(TAG, "OAuth token exchange failed code ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Could not exchange OAuth code")
            null
        }
    }

    suspend fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String
    ): TickTickOAuthTokenResponse? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            val request = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .header("Authorization", Credentials.basic(clientId, clientSecret))
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    json.decodeFromString<TickTickOAuthTokenResponse>(body)
                } else {
                    Log.e(TAG, "OAuth token refresh failed code ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Could not refresh OAuth token")
            null
        }
    }

    suspend fun fetchUserProfile(token: String): TickTickUserProfile? = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url("https://api.ticktick.com/open/v1/user/profile")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext json.decodeFromString<TickTickUserProfile>(body)
                }
            }

            // Fallback for personal access tokens (tp_...) via project endpoint
            val projRequest = Request.Builder()
                .url("https://api.ticktick.com/open/v1/project")
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(projRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val projects = json.decodeFromString<List<TickTickProject>>(body)
                    TickTickUserProfile(
                        username = "TickTick (${projects.size} Projects Synced)",
                        nickname = "Active Account"
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch user profile", e)
            null
        }
    }

    suspend fun verifyToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url("https://api.ticktick.com/open/v1/project")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify TickTick token", e)
            false
        }
    }

    suspend fun fetchCompletedTasksToday(token: String): List<TickTickWorkRecord> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext emptyList()
        val results = mutableListOf<TickTickWorkRecord>()
        val todayStr = isoDateFormat.format(Date())

        try {
            val projectRequest = Request.Builder()
                .url("https://api.ticktick.com/open/v1/project")
                .header("Authorization", "Bearer $token")
                .build()

            val projects = client.newCall(projectRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Project list failed: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string().orEmpty()
                try {
                    json.decodeFromString<List<TickTickProject>>(body)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse projects", e)
                    return@withContext emptyList()
                }
            }

            // Sync across open projects (cap to avoid rate limits, but more than before)
            for (project in projects.filterNot { it.closed }.take(30)) {
                try {
                    val dataRequest = Request.Builder()
                        .url("https://api.ticktick.com/open/v1/project/${project.id}/data")
                        .header("Authorization", "Bearer $token")
                        .build()

                    client.newCall(dataRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val data = try {
                                json.decodeFromString<TickTickProjectData>(body)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse project ${project.id}", e)
                                return@use
                            }
                            for (task in data.tasks) {
                                val isCompletedToday = task.status == 2 && task.completedTime?.startsWith(todayStr) == true
                                if (isCompletedToday) {
                                    results.add(
                                        TickTickWorkRecord(
                                            id = "api_${task.id}",
                                            title = task.title.ifBlank { "TickTick Task" },
                                            durationMinutes = 30,
                                            source = WorkRecordSource.TICKTICK_API,
                                            projectName = project.name
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing project ${project.id}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing TickTick tasks", e)
        }

        results
    }
}
