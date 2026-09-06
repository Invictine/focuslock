package com.focuslock.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Serializable
data class RemoteApp(val packageName: String, val appName: String, val isBlocked: Boolean, val category: String, val specificShortsOnly: Boolean = false)
@Serializable
data class RemoteSite(val domain: String, val displayName: String, val isBlocked: Boolean, val category: String, val isCustom: Boolean = false)
@Serializable
data class RemoteRecord(val recordId: String, val title: String, val durationMinutes: Int, val timestamp: Long, val source: String, val earnedMinutesCredited: Int, val projectName: String? = null)

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
    private val client = http ?: OkHttpClient()
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
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                val root = JSONObject(text)
                if (root.optString("status") != "success") return@withContext null
                // Query returns {"value":...}; mutation returns value directly or wrapped.
                if (root.has("value")) root.optJSONObject("value") ?: JSONObject().put("_primitive", root.opt("value"))
                else root
            }
        } catch (_: Exception) { null }
    }

    suspend fun getSnapshot(): Snapshot? {
        val raw = post("/api/query", "focus:getSnapshot", JSONObject()) ?: return null
        return try { parseSnapshot(raw) } catch (_: Exception) { null }
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
        } catch (_: Exception) { null }
    }

    data class Snapshot(
        val state: JSONObject?,
        val apps: List<RemoteApp>,
        val sites: List<RemoteSite>,
        val records: List<RemoteRecord>,
    )

    private fun parseSnapshot(root: JSONObject): Snapshot {
        val state = root.optJSONObject("state")
        fun obj(o: JSONObject, k: String, d: String = "") = o.optString(k, d)
        fun bool(o: JSONObject, k: String) = o.optBoolean(k, false)
        val apps = mutableListOf<RemoteApp>()
        root.optJSONArray("apps")?.let { arr ->
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                apps += RemoteApp(obj(o,"packageName"), obj(o,"appName"), bool(o,"isBlocked"), obj(o,"category","Social Media"), o.optBoolean("specificShortsOnly", false)) }
        }
        val sites = mutableListOf<RemoteSite>()
        root.optJSONArray("sites")?.let { arr ->
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                sites += RemoteSite(obj(o,"domain"), obj(o,"displayName"), bool(o,"isBlocked"), obj(o,"category","Social Media"), o.optBoolean("isCustom", false)) }
        }
        val records = mutableListOf<RemoteRecord>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                records += RemoteRecord(obj(o,"recordId"), obj(o,"title"), o.optInt("durationMinutes", 0), o.optLong("timestamp", 0L), obj(o,"source","MANUAL_ENTRY"), o.optInt("earnedMinutesCredited", 0), o.optString("projectName").ifBlank { null }) }
        }
        return Snapshot(state, apps, sites, records)
    }
}
