package com.focuslock.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Serializable
data class RemoteApp(val packageName: String, val appName: String, val isBlocked: Boolean, val category: String, val specificShortsOnly: Boolean = false, val updatedAt: Long = 0L)
@Serializable
data class RemoteSite(val domain: String, val displayName: String, val isBlocked: Boolean, val category: String, val isCustom: Boolean = false, val updatedAt: Long = 0L)
@Serializable
data class RemoteRecord(val recordId: String, val title: String, val durationMinutes: Int, val timestamp: Long, val source: String, val earnedMinutesCredited: Int, val projectName: String? = null)

data class UsageBucket(
    val date: String,
    val targetKind: String,
    val targetKey: String,
    val targetLabel: String,
    val category: String? = null,
    val trackedSeconds: Long,
    val blockedSeconds: Long? = null,
    val launchCount: Int? = null,
    val updatedAt: Long,
)

/** Per-device slice of `usage:getUsageSummary` (seconds are already aggregated server-side). */
data class UsageDeviceSummary(
    val deviceId: String,
    val deviceName: String,
    val trackedSeconds: Long,
    val blockedSeconds: Long,
)

/** Cross-device usage totals for the requested date window (see [ConvexSyncClient.getUsageSummary]). */
data class UsageSummary(
    val totalTrackedSeconds: Long,
    val devices: List<UsageDeviceSummary>,
)

/** A registered installation from `devices:listDevices`. */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val trackingStatus: String,
    val statusDetail: String?,
    val lastSeen: Long,
)

/**
 * Minimal Convex HTTP client (no codegen). Uses the deployment URL from
 * BuildConfig CONVEX_URL, e.g. https://happy-otter-123.convex.cloud
 * Endpoints: POST /api/query and /api/mutation with {"path","args"}.
 */
class ConvexSyncClient(
    private val convexUrl: String,
    private val tokenProvider: suspend () -> String?,
    http: OkHttpClient? = null,
) {
    private val client = http ?: sharedHttpClient
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    val json = Json { ignoreUnknownKeys = true }

    private suspend fun post(path: String, function: String, args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext null
        val body = JSONObject().put("path", function).put("args", args).toString()
            .toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(convexUrl.trimEnd('/') + path)
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("ConvexSync", "$function → HTTP ${resp.code}")
                    return@withContext null
                }
                val text = resp.body?.string() ?: return@withContext null
                val root = JSONObject(text)
                if (root.optString("status") != "success") {
                    android.util.Log.w("ConvexSync", "$function → ${root.optString("errorMessage", "non-success")}")
                    return@withContext null
                }
                // Query returns {"value":...}; mutation returns value directly or wrapped.
                if (root.has("value")) root.optJSONObject("value") ?: JSONObject().put("_primitive", root.opt("value"))
                else root
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ConvexSync", "$function network error: ${e.message}")
            null
        }
    }

    suspend fun getSnapshot(): Snapshot? {
        val raw = post("/api/query", "focus:getSnapshot", JSONObject()) ?: return null
        return try { parseSnapshot(raw) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
    }

    suspend fun saveState(balanceSec: Long, workSec: Long, scrollSec: Long, tasks: Int, date: String, updatedAt: Long): Boolean {
        val args = JSONObject()
            .put("creditBalanceSeconds", balanceSec)
            .put("totalWorkSecondsToday", workSec)
            .put("totalScrollSecondsToday", scrollSec)
            .put("tasksCompletedToday", tasks)
            .put("lastResetDate", date)
            .put("updatedAt", updatedAt)
        return post("/api/mutation", "focus:saveState", args) != null
    }

    suspend fun saveApps(apps: List<RemoteApp>, updatedAt: Long): Boolean {
        val arr = JSONArray()
        apps.forEach { arr.put(JSONObject()
            .put("packageName", it.packageName).put("appName", it.appName)
            .put("isBlocked", it.isBlocked).put("category", it.category)
            .put("specificShortsOnly", it.specificShortsOnly)) }
        return post("/api/mutation", "focus:saveBlockedApps", JSONObject().put("apps", arr).put("updatedAt", updatedAt)) != null
    }

    suspend fun saveSites(sites: List<RemoteSite>, updatedAt: Long): Boolean {
        val arr = JSONArray()
        sites.forEach { arr.put(JSONObject()
            .put("domain", it.domain).put("displayName", it.displayName)
            .put("isBlocked", it.isBlocked).put("category", it.category)
            .put("isCustom", it.isCustom)) }
        return post("/api/mutation", "focus:saveBlockedWebsites", JSONObject().put("sites", arr).put("updatedAt", updatedAt)) != null
    }

    /**
     * Throttled-fallback read of userPrefs via `focus:getDashboard` for deployments whose
     * `focus:getSnapshot` payload does not carry prefs. Callers should throttle this; the
     * dashboard payload is large. Returns null when no prefs row exists.
     */
    suspend fun getPrefs(): Prefs? {
        val raw = post("/api/query", "focus:getDashboard", JSONObject()) ?: return null
        val prefs = raw.optJSONObject("prefs") ?: return null
        return try {
            parsePrefs(prefs)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    suspend fun pushRecord(r: RemoteRecord): Boolean {
        val args = JSONObject()
            .put("recordId", r.recordId).put("title", r.title)
            .put("durationMinutes", r.durationMinutes).put("timestamp", r.timestamp)
            .put("source", r.source).put("earnedMinutesCredited", r.earnedMinutesCredited)
        if (r.projectName != null) args.put("projectName", r.projectName)
        return post("/api/mutation", "focus:addWorkRecord", args) != null
    }

    // --- Nuke mode (phone+PC sync) ---
    suspend fun getNuke(): JSONObject? {
        return post("/api/query", "nuke:getNuke", JSONObject())
    }

    suspend fun activateNuke(): Boolean {
        return post("/api/mutation", "nuke:activate", JSONObject()) != null
    }

    suspend fun completeNukeMeditation(): Boolean {
        return post("/api/mutation", "nuke:completeMeditation", JSONObject()) != null
    }

    /** Returns Pair(approved, reply). Uses /api/action for the LLM check-in. */
    suspend fun checkinNuke(message: String, history: List<Pair<String, String>> = emptyList()): Pair<Boolean, String>? = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext null
        val histArr = JSONArray()
        history.takeLast(6).forEach { (role, text) ->
            histArr.put(JSONObject().put("role", role).put("text", text))
        }
        val body = JSONObject().put("path", "nuke:checkin")
            .put("args", JSONObject().put("message", message).put("history", histArr)).toString()
            .toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(convexUrl.trimEnd('/') + "/api/action")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                val root = JSONObject(text)
                if (root.optString("status") != "success") return@withContext null
                val value = if (root.has("value")) root.optJSONObject("value") ?: JSONObject() else root
                val approved = value.optBoolean("approved", false)
                val reply = value.optString("reply", "Stay with it — tell me your plan.")
                Pair(approved, reply)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    // --- Device heartbeat & usage ---
    suspend fun heartbeatDevice(
        deviceId: String, name: String, appVersion: String,
        trackingStatus: String, statusDetail: String?, lastSeen: Long,
    ): Boolean {
        val args = JSONObject()
            .put("deviceId", deviceId).put("name", name)
            .put("appVersion", appVersion).put("platform", "android")
            .put("trackingStatus", trackingStatus)
            .put("lastSeen", lastSeen)
        if (statusDetail != null) args.put("statusDetail", statusDetail)
        return post("/api/mutation", "devices:heartbeat", args) != null
    }

    suspend fun recordUsageBatch(deviceId: String, buckets: List<UsageBucket>): Boolean {
        val arr = JSONArray()
        buckets.forEach { b ->
            arr.put(JSONObject()
                .put("date", b.date).put("targetKind", b.targetKind)
                .put("targetKey", b.targetKey).put("targetLabel", b.targetLabel)
                .put("trackedSeconds", b.trackedSeconds).put("updatedAt", b.updatedAt))
        }
        return post("/api/mutation", "usage:recordUsageBatch",
            JSONObject().put("deviceId", deviceId).put("buckets", arr)) != null
    }

    /**
     * Cross-device usage summary (`usage:getUsageSummary`). [fromDate]/[toDate] are
     * optional YYYY-MM-DD bounds; pass both as today's date to scope to one day.
     * Returns null on auth/network/parse failure — callers must tolerate that.
     */
    suspend fun getUsageSummary(fromDate: String? = null, toDate: String? = null): UsageSummary? {
        val args = JSONObject()
        if (fromDate != null) args.put("fromDate", fromDate)
        if (toDate != null) args.put("toDate", toDate)
        val raw = post("/api/query", "usage:getUsageSummary", args) ?: return null
        return try {
            parseUsageSummary(raw)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ConvexSync", "usage:getUsageSummary parse error: ${e.message}")
            null
        }
    }

    /** Registered devices (`devices:listDevices`), sorted by lastSeen desc. Empty on failure. */
    suspend fun listDevices(): List<DeviceInfo> {
        val raw = post("/api/query", "devices:listDevices", JSONObject()) ?: return emptyList()
        return try {
            // An array query result is wrapped by [post] as {"_primitive":[...]}.
            parseDevices(raw.optJSONArray("_primitive") ?: raw.optJSONArray("devices"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ConvexSync", "devices:listDevices parse error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Partial upsert of cross-platform user prefs. The server patches only the fields
     * supplied (see convex/focus.ts savePrefs); [updatedAt] is the LWW clock stamped on
     * whichever field(s) are set here, so callers must pass a non-null field only when
     * its local timestamp wins.
     */
    suspend fun savePrefs(
        workRatio: Int? = null,
        taskBonusMinutes: Int? = null,
        updatedAt: Long,
        strictMode: Boolean? = null,
        weeklyReport: Boolean? = null,
        dailyReminderMinutes: Int? = null,
        globalDailyCapMinutes: Int? = null,
    ): Boolean {
        val args = JSONObject().put("updatedAt", updatedAt)
        if (workRatio != null) {
            args.put("workRatio", workRatio)
            args.put("workRatioUpdatedAt", updatedAt)
        }
        if (taskBonusMinutes != null) {
            args.put("taskBonusMinutes", taskBonusMinutes)
            args.put("taskBonusMinutesUpdatedAt", updatedAt)
        }
        if (strictMode != null) args.put("strictMode", strictMode)
        if (weeklyReport != null) args.put("weeklyReport", weeklyReport)
        if (dailyReminderMinutes != null) args.put("dailyReminderMinutes", dailyReminderMinutes)
        if (globalDailyCapMinutes != null) args.put("globalDailyCapMinutes", globalDailyCapMinutes)
        return post("/api/mutation", "focus:savePrefs", args) != null
    }

    /** Cross-platform user prefs subset Android owns (work ratio + task bonus + strict mode). */
    data class Prefs(
        val workRatio: Int?,
        val workRatioUpdatedAt: Long,
        val taskBonusMinutes: Int?,
        val taskBonusMinutesUpdatedAt: Long,
        val strictMode: Boolean? = null,
        val weeklyReport: Boolean? = null,
        val dailyReminderMinutes: Int? = null,
        val globalDailyCapMinutes: Int? = null,
        val updatedAt: Long = 0L,
    )

    data class Snapshot(
        val state: JSONObject?,
        val apps: List<RemoteApp>,
        val sites: List<RemoteSite>,
        val records: List<RemoteRecord>,
        val prefs: Prefs? = null,
        val stateUpdatedAt: Long = 0L,
        val appsUpdatedAt: Long = 0L,
        val sitesUpdatedAt: Long = 0L,
    )

    private fun parseSnapshot(root: JSONObject): Snapshot {
        val state = root.optJSONObject("state")
        val stateUpdatedAt = root.optLong("stateUpdatedAt", 0L)
        val appsUpdatedAt = root.optLong("appsUpdatedAt", 0L)
        val sitesUpdatedAt = root.optLong("sitesUpdatedAt", 0L)
        fun obj(o: JSONObject, k: String, d: String = "") = o.optString(k, d)
        fun bool(o: JSONObject, k: String) = o.optBoolean(k, false)
        // optJSONObject + skip (like parseUsageSummary/parseDevices): one malformed
        // element used to throw and null the whole snapshot, permanently killing sync.
        val apps = mutableListOf<RemoteApp>()
        root.optJSONArray("apps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                apps += RemoteApp(obj(o,"packageName"), obj(o,"appName"), bool(o,"isBlocked"), obj(o,"category","Social Media"), o.optBoolean("specificShortsOnly", false))
            }
        }
        val sites = mutableListOf<RemoteSite>()
        root.optJSONArray("sites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                sites += RemoteSite(obj(o,"domain"), obj(o,"displayName"), bool(o,"isBlocked"), obj(o,"category","Social Media"), o.optBoolean("isCustom", false))
            }
        }
        val records = mutableListOf<RemoteRecord>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                records += RemoteRecord(obj(o,"recordId"), obj(o,"title"), o.optInt("durationMinutes", 0), o.optLong("timestamp", 0L), obj(o,"source","MANUAL_ENTRY"), o.optInt("earnedMinutesCredited", 0), o.optString("projectName").ifBlank { null })
            }
        }
        val prefs = root.optJSONObject("prefs")?.let { parsePrefs(it) }
        return Snapshot(state, apps, sites, records, prefs, stateUpdatedAt, appsUpdatedAt, sitesUpdatedAt)
    }

    /** Defensive parse of the `usage:getUsageSummary` value object. */
    private fun parseUsageSummary(root: JSONObject): UsageSummary {
        val devices = mutableListOf<UsageDeviceSummary>()
        root.optJSONArray("devices")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val deviceId = o.optString("deviceId").trim()
                if (deviceId.isEmpty()) continue
                devices += UsageDeviceSummary(
                    deviceId = deviceId,
                    deviceName = o.optString("deviceName").trim().ifEmpty { "Unknown device" },
                    trackedSeconds = o.optLong("trackedSeconds", 0L).coerceAtLeast(0L),
                    blockedSeconds = o.optLong("blockedSeconds", 0L).coerceAtLeast(0L),
                )
            }
        }
        val reportedTotal = root.optLong("totalTrackedSeconds", -1L)
        return UsageSummary(
            totalTrackedSeconds = if (reportedTotal >= 0L) reportedTotal else devices.sumOf { it.trackedSeconds },
            devices = devices.sortedByDescending { it.trackedSeconds },
        )
    }

    /** Defensive parse of the `devices:listDevices` array (already sorted server-side). */
    private fun parseDevices(arr: JSONArray?): List<DeviceInfo> {
        if (arr == null) return emptyList()
        val devices = mutableListOf<DeviceInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val deviceId = o.optString("deviceId").trim()
            if (deviceId.isEmpty()) continue
            devices += DeviceInfo(
                deviceId = deviceId,
                name = o.optString("name").trim().ifEmpty { "Unknown device" },
                platform = o.optString("platform").trim().ifEmpty { "unknown" },
                appVersion = o.optString("appVersion").trim().ifEmpty { "unknown" },
                trackingStatus = o.optString("trackingStatus", "active").ifEmpty { "active" },
                statusDetail = o.optString("statusDetail").ifBlank { null },
                lastSeen = o.optLong("lastSeen", 0L),
            )
        }
        return devices.sortedByDescending { it.lastSeen }
    }

    companion object {
        /**
         * Process-wide OkHttp client shared by every [ConvexSyncClient] instance so the
         * connection pool, dispatcher threads, and DNS cache are reused across cycles.
         * Connection pool: 5 idle connections kept for 5 minutes (per audit item 9).
         */
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    /** Defensive parse: missing/null fields stay null so callers can treat them as no-ops. */
    private fun parsePrefs(p: JSONObject): Prefs = Prefs(
        workRatio = if (p.has("workRatio") && !p.isNull("workRatio")) p.optInt("workRatio") else null,
        workRatioUpdatedAt = p.optLong("workRatioUpdatedAt", 0L),
        taskBonusMinutes = if (p.has("taskBonusMinutes") && !p.isNull("taskBonusMinutes")) p.optInt("taskBonusMinutes") else null,
        taskBonusMinutesUpdatedAt = p.optLong("taskBonusMinutesUpdatedAt", 0L),
        strictMode = if (p.has("strictMode") && !p.isNull("strictMode")) p.optBoolean("strictMode") else null,
        weeklyReport = if (p.has("weeklyReport") && !p.isNull("weeklyReport")) p.optBoolean("weeklyReport") else null,
        dailyReminderMinutes = if (p.has("dailyReminderMinutes") && !p.isNull("dailyReminderMinutes")) p.optInt("dailyReminderMinutes") else null,
        globalDailyCapMinutes = if (p.has("globalDailyCapMinutes") && !p.isNull("globalDailyCapMinutes")) p.optInt("globalDailyCapMinutes") else null,
        updatedAt = p.optLong("updatedAt", 0L),
    )
}
