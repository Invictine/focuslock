package com.focuslock.app.service

import android.net.Uri
import android.util.Log
import com.focuslock.app.data.model.TickTickWorkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
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

/** One completed-today task awaiting final sort (project order → newest-first by completion). */
private data class PendingCompletedTask(val title: String, val project: String, val completedMillis: Long)

class TickTickApiClient {

    // The OkHttp client moved to the companion (sharedHttpClient): every TickTickApiClient()
    // call site (dashboard, settings, blocker) now shares one process-wide connection pool,
    // dispatcher thread pool and DNS cache instead of building a fresh client per instance.

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

        /** Max concurrent per-project data requests (was strictly sequential, up to 30 calls). */
        private const val MAX_PARALLEL_PROJECT_REQUESTS = 6

        /** Display-only TTL for the completed-today result; matches the dashboard refetch gate. */
        private const val COMPLETED_TODAY_TTL_MS = 3L * 60L * 1000L

        /**
         * Process-wide OkHttp client shared by every [TickTickApiClient] instance so the
         * connection pool, dispatcher threads, and DNS cache are reused across screens.
         * Same timeout config as the previous per-instance client (15s connect/read).
         */
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** Cache entry for [fetchCompletedTaskTitlesToday]; success results only, day-keyed. */
        private class CompletedTodayEntry(val titles: List<Pair<String, String>>, val fetchedAtMs: Long)

        @Volatile private var completedTodayCache: Pair<String, CompletedTodayEntry>? = null
        private val completedTodayCacheLock = Any()

        /** Day + token-hash key, so a token swap or day rollover never serves stale data. */
        private fun completedTodayCacheKey(token: String): String =
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ":" +
                Integer.toHexString(token.hashCode())

        /**
         * Loopback redirect for a specific bound port (port-fallback fix, item 8):
         * when 8080 is taken the loopback server binds an alternate port and the
         * authorize/token URLs must use the same port. Defaults to the canonical
         * port so existing callers and the portal registration keep matching.
         */
        const val DEFAULT_REDIRECT_PORT = 8080

        fun redirectUri(redirectPort: Int = DEFAULT_REDIRECT_PORT): String =
            if (redirectPort == DEFAULT_REDIRECT_PORT) REDIRECT_URI
            else "http://127.0.0.1:$redirectPort/"

        internal fun authorizationCodeRequest(
            clientId: String,
            clientSecret: String,
            code: String,
            redirectPort: Int = DEFAULT_REDIRECT_PORT
        ): Request =
            Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .header("Authorization", Credentials.basic(clientId, clientSecret))
                .post(FormBody.Builder()
                    .add("code", code)
                    .add("grant_type", "authorization_code")
                    .add("scope", "tasks:read tasks:write")
                    .add("redirect_uri", redirectUri(redirectPort))
                    .build())
                .build()

        fun buildAuthorizeUrl(clientId: String, state: String, redirectPort: Int = DEFAULT_REDIRECT_PORT): String {
            val encodedClient = java.net.URLEncoder.encode(clientId, "UTF-8")
            val encodedRedirect = java.net.URLEncoder.encode(redirectUri(redirectPort), "UTF-8")
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
        code: String,
        redirectPort: Int = DEFAULT_REDIRECT_PORT
    ): TickTickOAuthTokenResponse? = withContext(Dispatchers.IO) {
        try {
            val request = authorizationCodeRequest(clientId, clientSecret, code, redirectPort)

            sharedHttpClient.newCall(request).execute().use { response ->
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

            sharedHttpClient.newCall(request).execute().use { response ->
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

            sharedHttpClient.newCall(request).execute().use { response ->
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
            sharedHttpClient.newCall(projRequest).execute().use { response ->
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

            sharedHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify TickTick token", e)
            false
        }
    }

    /**
     * Tasks NEVER count as focus. Focus comes only from logged focus durations
     * (TickTick focus/pomodoro sessions with an explicit duration, or the
     * user-operated Focus Timer / manual log). This function intentionally
     * returns an empty list so no caller can award work minutes or credits for
     * completed tasks. Callers must treat empty as "no focus sessions found —
     * tasks don't count" and must NOT award credits for tasks.
     *
     * Signature is kept so existing callers (Settings sync, Blocker verify)
     * keep compiling. Use [fetchCompletedTaskTitlesToday] for display-only
     * task stats (titles give 0 focus minutes).
     */
    suspend fun fetchCompletedTasksToday(token: String): List<TickTickWorkRecord> = withContext(Dispatchers.IO) {
        // Tasks never count as focus; focus comes only from logged focus durations.
        if (token.isBlank()) return@withContext emptyList()
        emptyList()
    }

    /**
     * Display-only helper: titles of tasks completed today, for stats/debug UI.
     * These carry ZERO focus minutes and must never be passed to
     * CreditBankRepository.recordWorkCredit as work.
     *
     * COUNTING RULE (done-today only, never overdue/stale): a task counts iff
     * status == 2 AND its parsed completedTime falls in
     * [startOfTodayMillis(device TZ), now]. dueDate/startDate are NEVER used
     * for counting, so overdue tasks can never leak in. Results newest-first
     * by completedMillis.
     *
     * Performance: the per-project data requests run in parallel, capped at
     * [MAX_PARALLEL_PROJECT_REQUESTS] concurrent calls (previously up to 30
     * strictly sequential round trips), with results kept in project order.
     * Successful results are served from a 3-minute in-memory TTL cache keyed
     * by day + token hash — this is display-only data, so short staleness is
     * acceptable. Failures (project-list HTTP/parse errors) are never cached;
     * [bypassCache] = true (pull-to-refresh) always hits the network.
     */
    suspend fun fetchCompletedTaskTitlesToday(
        token: String,
        bypassCache: Boolean = false
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext emptyList()
        val cacheKey = completedTodayCacheKey(token)
        if (!bypassCache) {
            val cached = completedTodayCache
            if (cached != null && cached.first == cacheKey &&
                System.currentTimeMillis() - cached.second.fetchedAtMs < COMPLETED_TODAY_TTL_MS
            ) {
                return@withContext cached.second.titles
            }
        }
        val fresh = fetchCompletedTaskTitlesTodayUncached(token) ?: return@withContext emptyList()
        // Cache only success results (null = project-list failure, never cached).
        synchronized(completedTodayCacheLock) {
            completedTodayCache = cacheKey to CompletedTodayEntry(fresh, System.currentTimeMillis())
        }
        fresh
    }

    /**
     * Network path of [fetchCompletedTaskTitlesToday]. Returns null when the request
     * failed outright (project list unreachable/unparseable) so callers don't cache it;
     * per-project failures are tolerated and yield a valid partial result, exactly as
     * the previous sequential loop did.
     */
    private suspend fun fetchCompletedTaskTitlesTodayUncached(token: String): List<Pair<String, String>>? =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<PendingCompletedTask>()
            // Device-TZ day boundary; must agree with CreditBankRepository.startOfTodayMillis().
            val startOfToday = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val now = System.currentTimeMillis()

            try {
                val projectRequest = Request.Builder()
                    .url("https://api.ticktick.com/open/v1/project")
                    .header("Authorization", "Bearer $token")
                    .build()

                val projects = sharedHttpClient.newCall(projectRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Project list failed: ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string().orEmpty()
                    try {
                        json.decodeFromString<List<TickTickProject>>(body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse projects", e)
                        return@withContext null
                    }
                }

                // Per-project data fetches run in parallel, capped by a semaphore; map +
                // awaitAll preserve project order, so the collected content is identical
                // to the old sequential loop (final sort is newest-first anyway).
                val projectSemaphore = Semaphore(MAX_PARALLEL_PROJECT_REQUESTS)
                val perProjectResults = coroutineScope {
                    projects.filterNot { it.closed }.take(30).map { project ->
                        async {
                            projectSemaphore.withPermit {
                                fetchProjectCompletedToday(token, project, startOfToday, now)
                            }
                        }
                    }.awaitAll()
                }
                for (projectResults in perProjectResults) {
                    results.addAll(projectResults)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error syncing TickTick tasks", e)
                return@withContext null
            }

            results.sortedByDescending { it.completedMillis }.map { it.title to it.project }
        }

    /**
     * Completed-today entries for ONE project. Failures (HTTP error, parse error,
     * exception) contribute nothing — same tolerance as the old sequential loop.
     */
    private fun fetchProjectCompletedToday(
        token: String,
        project: TickTickProject,
        startOfToday: Long,
        now: Long
    ): List<PendingCompletedTask> {
        val found = mutableListOf<PendingCompletedTask>()
        try {
            val dataRequest = Request.Builder()
                .url("https://api.ticktick.com/open/v1/project/${project.id}/data")
                .header("Authorization", "Bearer $token")
                .build()

            sharedHttpClient.newCall(dataRequest).execute().use { response ->
                if (!response.isSuccessful) return found
                val body = response.body?.string().orEmpty()
                val data = try {
                    json.decodeFromString<TickTickProjectData>(body)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse project ${project.id}", e)
                    return found
                }
                for (task in data.tasks) {
                    // Guard: dueDate/startDate are never consulted; only completedTime counts.
                    if (task.status != 2) continue
                    val completedMillis = parseCompletedMillis(task.completedTime) ?: continue
                    if (completedMillis in startOfToday..now) {
                        // Display only: 0 focus minutes. Never creditable.
                        found.add(
                            PendingCompletedTask(
                                task.title.ifBlank { "TickTick Task" },
                                project.name,
                                completedMillis
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing project ${project.id}", e)
        }
        return found
    }

    /**
     * TZ-safe parse of TickTick completedTime to epoch millis. Tries offset-aware
     * ISO-8601 first (handles 'Z' and '+hh:mm'), then bare local patterns
     * ("yyyy-MM-dd'T'HH:mm:ss" with/without millis) interpreted in the device TZ.
     * Returns null when unparseable (task is then ignored, never counted).
     */
    private fun parseCompletedMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        // 1) Offset-aware: "2026-09-12T10:15:30.123Z", "...+02:00", or plain ISO instant.
        try {
            return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: Exception) { }
        try {
            return java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) { }
        // 2) Bare local date-times in device TZ.
        val localPatterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss")
        for (pattern in localPatterns) {
            try {
                val ldt = java.time.LocalDateTime.parse(
                    trimmed, java.time.format.DateTimeFormatter.ofPattern(pattern)
                )
                return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) { }
        }
        // 3) Legacy SimpleDateFormat fallback (device TZ).
        for (pattern in localPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                val parsed = sdf.parse(trimmed) ?: continue
                return parsed.time
            } catch (_: Exception) { }
        }
        return null
    }
}
