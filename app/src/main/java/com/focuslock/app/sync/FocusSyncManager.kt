package com.focuslock.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.service.UsageTrackerHelper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.security.MessageDigest
import kotlin.random.Random

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val detail: String) : SyncStatus
    data class Skipped(val reason: String) : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/** Bidirectional, timestamp-aware Clerk/Convex synchronization. */
class FocusSyncManager(
    context: Context,
    private val bank: CreditBankRepository,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext
    // Default CoroutineExceptionHandler: a bug in a launched cycle must log, not kill the process.
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        android.util.Log.w("FocusSyncManager", "uncaught sync coroutine error", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var loop: Job? = null
    private val identityPrefs = appContext.getSharedPreferences("focuslock_device", Context.MODE_PRIVATE)
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Serializes the auto-sync loop and UI-triggered [syncNowAsync] (audit item 8). */
    private val syncMutex = Mutex()

    /** Consecutive failed cycles; drives the exponential backoff in the auto-sync loop. */
    private var consecutiveFailures = 0

    /** Latest Clerk token, read by the cached client's token provider (audit item 9). */
    @Volatile private var currentToken: String? = null

    /**
     * Highest timestamp ever observed from the server (snapshot fields + prefs clocks).
     * Hybrid-clock anchor for the writeTime clamp (clock-skew fix, audit item 3).
     */
    @Volatile private var serverSeenMs = 0L

    // One client per base URL; the shared OkHttp client is pooled process-wide.
    @Volatile private var cachedClient: ConvexSyncClient? = null
    @Volatile private var cachedClientUrl: String? = null

    // Fallback cache for backends whose getSnapshot payload has no prefs: a throttled
    // getDashboard fetch so the 30s sync loop never adds a heavy call every cycle.
    @Volatile private var cachedRemotePrefs: ConvexSyncClient.Prefs? = null
    @Volatile private var lastPrefsFetchAt = 0L

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private fun clientFor(url: String): ConvexSyncClient {
        val existing = cachedClient
        if (existing != null && cachedClientUrl == url) return existing
        return ConvexSyncClient(url, tokenProvider = { currentToken }).also {
            cachedClient = it
            cachedClientUrl = url
        }
    }

    fun startAutoSync(auth: AuthViewModel) {
        stopAutoSync()
        loop = scope.launch {
            while (isActive) {
                // null = cycle was skipped (offline, or lock held elsewhere) and must
                // not touch the backoff — an offline stretch must not feed it (item 9).
                var succeeded: Boolean? = null
                var offline = false
                try {
                    if (!hasNetworkConnection()) {
                        // Skip the cycle entirely instead of letting OkHttp time out per call.
                        _status.value = SyncStatus.Skipped("Offline — waiting for network")
                        offline = true
                    } else if (syncMutex.tryLock()) {
                        try {
                            succeeded = performSync(auth)
                        } finally {
                            syncMutex.unlock()
                        }
                    } else {
                        // Another (UI-triggered) sync is in flight; don't pile on and
                        // don't reset the backoff for a cycle that never ran.
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _status.value = SyncStatus.Error("Sync failed: ${e.message?.take(120) ?: "network"}")
                    succeeded = false
                }
                consecutiveFailures = when (succeeded) {
                    true -> 0
                    false -> (consecutiveFailures + 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
                    null -> consecutiveFailures
                }
                // While offline, poll at the base cadence instead of the failure backoff
                // so a regained connection is picked up quickly; a real attempt then
                // resets the backoff on success (item 9).
                delay(nextLoopDelayMs(consecutiveFailures, offline))
            }
        }
    }

    fun stopAutoSync() { loop?.cancel(); loop = null }

    fun syncNowAsync(auth: AuthViewModel) = scope.launch { syncNow(auth) }

    /** UI entry point. Never runs concurrently with the auto-sync loop (audit item 8). */
    suspend fun syncNow(auth: AuthViewModel) {
        if (!syncMutex.tryLock()) {
            _status.value = SyncStatus.Skipped("Already syncing")
            return
        }
        try {
            performSync(auth)
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Runs one full sync cycle. Must only be called while holding [syncMutex].
     * Returns true when no step failed (used to reset the backoff).
     */
    private suspend fun performSync(auth: AuthViewModel): Boolean {
        if (!auth.isConfigured()) {
            _status.value = SyncStatus.Skipped("Clerk not configured — offline mode")
            return true
        }
        val token = try { auth.getConvexToken() } catch (_: Exception) { null }
        if (token.isNullOrBlank()) {
            _status.value = SyncStatus.Skipped("Not signed in")
            return true
        }
        currentToken = token
        val url = try { com.focuslock.app.BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
        if (url.isBlank() || !url.startsWith("http")) {
            _status.value = SyncStatus.Skipped("Convex URL missing — set convex.url")
            return true
        }
        val convex = clientFor(url)

        _status.value = SyncStatus.Syncing
        try {
            val startedAt = System.currentTimeMillis()
            val lastSuccessfulSync = bank.getLastSyncTimestamp()
            val snapshot = convex.getSnapshot()
                ?: throw IllegalStateException("Could not load the server snapshot")

            // Clock-skew fix (item 3): anchor the hybrid clock on the freshest
            // server-seen stamp before any write decision. See [clampedWriteTime].
            serverSeenMs = maxOf(
                serverSeenMs,
                snapshot.stateUpdatedAt,
                snapshot.appsUpdatedAt,
                snapshot.sitesUpdatedAt,
                snapshot.prefs?.updatedAt ?: 0L,
                snapshot.prefs?.workRatioUpdatedAt ?: 0L,
                snapshot.prefs?.taskBonusMinutesUpdatedAt ?: 0L,
            )

            // Lost-update fix (item 1): re-read the bank AFTER getSnapshot (which can
            // block for up to 60s), under the bank's state lock, immediately before
            // the push/pull decision. Credits earned while the snapshot was in flight
            // are part of this fresh read instead of being wiped by a stale push, and
            // the LWW guard inside applyRemoteState refuses the re-stamp if anything
            // newer lands between this read and the commit.
            val fresh = bank.withStateLock { bank.readAggregateState() }
            val localBalance = fresh.balanceSeconds
            val localStateUpdatedAt = fresh.stateUpdatedAt
            val remoteStateUpdatedAt = snapshot.stateUpdatedAt
            val hasMeaningfulLocalState = localBalance > 0L ||
                fresh.workSecondsToday > 0L ||
                fresh.scrollSecondsToday > 0L ||
                fresh.tasksCompletedToday > 0

            var pulled = 0
            var pushed = 0
            val stepErrors = mutableListOf<String>()

            try {
            val remoteStateWins = snapshot.state != null && remoteStateUpdatedAt > localStateUpdatedAt &&
                !(lastSuccessfulSync == 0L && localStateUpdatedAt == 0L && hasMeaningfulLocalState)
            if (remoteStateWins) {
                val state = snapshot.state
                bank.applyRemoteState(
                    balanceSeconds = state.optLong("creditBalanceSeconds", 0L),
                    workSecondsToday = state.optLong("totalWorkSecondsToday", 0L),
                    scrollSecondsToday = state.optLong("totalScrollSecondsToday", 0L),
                    tasksCompletedToday = state.optInt("tasksCompletedToday", 0),
                    lastResetDate = state.optString("lastResetDate", today()),
                    updatedAt = remoteStateUpdatedAt,
                )
                pulled++
            } else if (snapshot.state == null || localStateUpdatedAt > remoteStateUpdatedAt ||
                (lastSuccessfulSync == 0L && hasMeaningfulLocalState)) {
                // Push the freshly-read values (never the pre-network ones) with a
                // skew-clamped writeTime; applyRemoteState re-stamps locally unless a
                // newer local write (e.g. a credit) made the stamp stale already.
                val writeTime = clampedWriteTime(startedAt, fresh.stateUpdatedAt)
                require(convex.saveState(
                    localBalance,
                    fresh.workSecondsToday,
                    fresh.scrollSecondsToday,
                    fresh.tasksCompletedToday,
                    fresh.lastResetDate.ifBlank { today() },
                    writeTime,
                )) { "Could not save focus state" }
                bank.applyRemoteState(
                    localBalance,
                    fresh.workSecondsToday,
                    fresh.scrollSecondsToday,
                    fresh.tasksCompletedToday,
                    fresh.lastResetDate.ifBlank { today() },
                    writeTime,
                )
                pushed++
            }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "focus-state sync step failed", e)
                stepErrors += "focus state: ${e.message?.take(120) ?: "network"}"
            }

            try {
            val localApps = settings.getBlockedApps()
            val localAppsUpdatedAt = settings.getBlockedAppsUpdatedAt()
            val hasCustomizedLocalApps = localApps != BlockedApp.DEFAULT_DOOMSCROLL_APPS
            if (snapshot.appsUpdatedAt > localAppsUpdatedAt &&
                !(lastSuccessfulSync == 0L && localAppsUpdatedAt == 0L && hasCustomizedLocalApps)) {
                settings.applyRemoteBlockedApps(snapshot.apps.map(RemoteApp::toLocal), snapshot.appsUpdatedAt)
                pulled++
            } else if (snapshot.appsUpdatedAt == 0L || localAppsUpdatedAt > snapshot.appsUpdatedAt || lastSuccessfulSync == 0L) {
                val writeTime = clampedWriteTime(startedAt, localAppsUpdatedAt)
                require(convex.saveApps(localApps.map { it.toRemote() }, writeTime)) { "Could not save app boundaries" }
                settings.applyRemoteBlockedApps(localApps, writeTime)
                pushed++
            }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "app-boundaries sync step failed", e)
                stepErrors += "app boundaries: ${e.message?.take(120) ?: "network"}"
            }

            try {
            val localSites = settings.getBlockedWebsites()
            val localSitesUpdatedAt = settings.getBlockedWebsitesUpdatedAt()
            val hasCustomizedLocalSites = localSites != BlockedWebsite.DEFAULT_BLOCKED_WEBSITES
            if (snapshot.sitesUpdatedAt > localSitesUpdatedAt &&
                !(lastSuccessfulSync == 0L && localSitesUpdatedAt == 0L && hasCustomizedLocalSites)) {
                settings.applyRemoteBlockedWebsites(snapshot.sites.map(RemoteSite::toLocal), snapshot.sitesUpdatedAt)
                pulled++
            } else if (snapshot.sitesUpdatedAt == 0L || localSitesUpdatedAt > snapshot.sitesUpdatedAt || lastSuccessfulSync == 0L) {
                val writeTime = clampedWriteTime(startedAt, localSitesUpdatedAt)
                require(convex.saveSites(localSites.map { it.toRemote() }, writeTime)) { "Could not save website boundaries" }
                settings.applyRemoteBlockedWebsites(localSites, writeTime)
                pushed++
            }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "website-boundaries sync step failed", e)
                stepErrors += "website boundaries: ${e.message?.take(120) ?: "network"}"
            }

            try {
            // Cross-platform prefs: work ratio + task bonus, each independently LWW.
            val remotePrefs = snapshot.prefs

            val localWorkRatio = settings.getWorkRatio()
            val localWorkRatioUpdatedAt = settings.getWorkRatioUpdatedAt()
            val remoteWorkRatio = remotePrefs?.workRatio
            val remoteWorkRatioUpdatedAt = remotePrefs?.workRatioUpdatedAt ?: 0L
            if (remoteWorkRatio != null && remoteWorkRatioUpdatedAt > localWorkRatioUpdatedAt) {
                settings.applyRemoteWorkRatio(remoteWorkRatio, remoteWorkRatioUpdatedAt)
                pulled++
            } else if (localWorkRatioUpdatedAt > remoteWorkRatioUpdatedAt) {
                val writeTime = clampedWriteTime(startedAt, localWorkRatioUpdatedAt)
                require(convex.savePrefs(workRatio = localWorkRatio, updatedAt = writeTime)) {
                    "Could not save work-to-scroll ratio"
                }
                // Re-stamp local so the pushed clock matches the server and we don't re-pull.
                settings.applyRemoteWorkRatio(localWorkRatio, writeTime)
                pushed++
            }

            val localTaskBonus = settings.getTaskBonus()
            val localTaskBonusUpdatedAt = settings.getTaskBonusUpdatedAt()
            val remoteTaskBonus = remotePrefs?.taskBonusMinutes
            val remoteTaskBonusUpdatedAt = remotePrefs?.taskBonusMinutesUpdatedAt ?: 0L
            if (remoteTaskBonus != null && remoteTaskBonusUpdatedAt > localTaskBonusUpdatedAt) {
                settings.applyRemoteTaskBonus(remoteTaskBonus, remoteTaskBonusUpdatedAt)
                pulled++
            } else if (localTaskBonusUpdatedAt > remoteTaskBonusUpdatedAt) {
                val writeTime = clampedWriteTime(startedAt, localTaskBonusUpdatedAt)
                require(convex.savePrefs(taskBonusMinutes = localTaskBonus, updatedAt = writeTime)) {
                    "Could not save task bonus"
                }
                settings.applyRemoteTaskBonus(localTaskBonus, writeTime)
                pushed++
            }

            // Strict / Lockdown mode: independent LWW clock. Current backends return prefs
            // in getSnapshot; older ones fall back to a throttled getDashboard fetch.
            val effectivePrefs = remotePrefs ?: fetchRemotePrefsThrottled(convex)
            val localLockdown = settings.lockdownModeFlow.first()
            val localLockdownUpdatedAt = settings.getLockdownModeUpdatedAt()
            val remoteStrictMode = effectivePrefs?.strictMode
            val remoteLockdownUpdatedAt = effectivePrefs?.updatedAt ?: 0L
            if (remoteStrictMode != null && remoteLockdownUpdatedAt > localLockdownUpdatedAt) {
                settings.applyRemoteLockdown(
                    enabled = remoteStrictMode,
                    updatedAt = remoteLockdownUpdatedAt,
                    enabledAt = remoteLockdownUpdatedAt,
                )
                pulled++
            } else if (localLockdownUpdatedAt > 0L &&
                localLockdownUpdatedAt > remoteLockdownUpdatedAt &&
                (remoteStrictMode == null || localLockdown != remoteStrictMode)
            ) {
                val writeTime = clampedWriteTime(startedAt, localLockdownUpdatedAt)
                require(
                    convex.savePrefs(
                        strictMode = localLockdown,
                        weeklyReport = effectivePrefs?.weeklyReport,
                        dailyReminderMinutes = effectivePrefs?.dailyReminderMinutes,
                        globalDailyCapMinutes = effectivePrefs?.globalDailyCapMinutes,
                        updatedAt = writeTime,
                    )
                ) { "Could not save lockdown preference" }
                // Re-stamp local so the pushed clock matches the server and we don't re-push.
                settings.applyRemoteLockdown(localLockdown, writeTime, writeTime)
                pushed++
            }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "prefs sync step failed", e)
                stepErrors += "prefs: ${e.message?.take(120) ?: "network"}"
            }

            try {
            // Push only records not yet confirmed on the server (audit item 6).
            val pushOutcome = pushNewWorkRecords(convex, snapshot.records)
            pushed += pushOutcome.pushed
            if (pushOutcome.failed) stepErrors += "work history: push interrupted — will retry"
            pulled += bank.mergeRemoteWorkRecords(snapshot.records.map(RemoteRecord::toLocal))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "work-history sync step failed", e)
                stepErrors += "work history: ${e.message?.take(120) ?: "network"}"
            }

            try {
                syncNuke(convex)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "nuke sync step failed", e)
                stepErrors += "nuke: ${e.message?.take(120) ?: "network"}"
            }

            try {
                syncDeviceAndUsage(convex, startedAt)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "device/usage sync step failed", e)
                stepErrors += "device/usage: ${e.message?.take(120) ?: "network"}"
            }

            if (stepErrors.isNotEmpty()) {
                android.util.Log.w("FocusSyncManager", "partial sync: ${stepErrors.joinToString("; ")}")
            }
            // Stamp the first-sync guard ONLY on a fully-clean cycle (item 4): a
            // partial success used to clear lastSuccessfulSync == 0L, which then let
            // remote defaults overwrite customized local boundaries on the next cycle.
            if (stepErrors.isEmpty()) {
                bank.setLastSyncTimestamp(System.currentTimeMillis())
                val finishedAt = System.currentTimeMillis()
                _status.value = SyncStatus.Done(
                    "Synced · ↑$pushed ↓$pulled · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(finishedAt))}",
                )
            } else {
                val finishedAt = System.currentTimeMillis()
                _status.value = SyncStatus.Error(
                    "Sync failed: ${stepErrors.firstOrNull()?.take(120) ?: "network"}" +
                        " · ↑$pushed ↓$pulled · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(finishedAt))}",
                )
            }
            return stepErrors.isEmpty()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("FocusSyncManager", "syncNow failed", e)
            _status.value = SyncStatus.Error("Sync failed: ${e.message?.take(120) ?: "network"}")
            return false
        }
    }

    private data class RecordPushOutcome(val pushed: Int, val failed: Boolean)

    /**
     * Incremental work-history upload (audit item 6). The server snapshot is the
     * source of truth for what is already uploaded: every local record whose id is
     * absent from the snapshot is pushed, oldest first. Deriving the pending set
     * from [remoteRecords] covers records older than any progress cursor —
     * backdated logs, records dropped by a server-side history cap, and local
     * history pruned after a failed push. The first failure stops the loop so the
     * next cycle retries the remaining records.
     */
    private suspend fun pushNewWorkRecords(
        convex: ConvexSyncClient,
        remoteRecords: List<RemoteRecord>,
    ): RecordPushOutcome {
        val remoteIds = remoteRecords.asSequence().map { it.recordId }.toHashSet()
        val pending = bank.fullHistoryFlow.first()
            .filter { it.id !in remoteIds }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))
        var pushed = 0
        for (record in pending) {
            if (!convex.pushRecord(record.toRemote())) {
                android.util.Log.w("FocusSyncManager", "work-record push failed at ${record.id}; will retry next cycle")
                return RecordPushOutcome(pushed, failed = true)
            }
            pushed++
        }
        return RecordPushOutcome(pushed, failed = false)
    }

    /** Cheap pre-flight check so an offline cycle returns instantly (audit item 7). */
    private fun hasNetworkConnection(): Boolean {
        val cm = connectivityManager ?: return true
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Hybrid LWW clock (clock-skew fix, item 3). The protocol exposes no
     * authoritative server clock — snapshot timestamps were written by other
     * devices' clocks and mutations return no server-echoed time — so local write
     * stamps are biased with the highest server-seen stamp [serverSeenMs]:
     *
     *     writeTime = max(localUpdatedAt, min(deviceNow, serverSeenMs + SKEW_CLAMP_MS))
     *
     * - Device clock AHEAD of the fleet: min() caps the stamp near the observed
     *   server floor, so remote writes can win LWW again instead of being locked
     *   out forever (the old unbounded maxOf made the stamp ever-growing).
     * - Device clock BEHIND: max() adopts the server-seen time (bounded by the
     *   clamp), so local writes are not permanently outranked by honest clocks.
     * - Honest clock: serverSeenMs lags deviceNow by more than the clamp in the
     *   common quiet-fleet case, so min() picks deviceNow and behaviour matches
     *   the previous maxOf(startedAt, localUpdatedAt).
     * The result never goes below [localUpdatedAt]: per-device stamps stay
     * monotonic, so a local change is never silently aged backwards.
     */
    private fun clampedWriteTime(startedAt: Long, localUpdatedAt: Long): Long {
        val deviceNow = maxOf(startedAt, localUpdatedAt)
        val serverFloor = serverSeenMs
        if (serverFloor <= 0L) return deviceNow
        return maxOf(localUpdatedAt, minOf(deviceNow, serverFloor + SKEW_CLAMP_MS))
    }

    /** Exponential backoff + jitter, capped at [MAX_BACKOFF_DELAY_MS] (audit item 7). */
    private fun nextLoopDelayMs(failures: Int, offline: Boolean = false): Long {
        // Offline cycles are neutral: keep polling for connectivity at the base rate.
        if (offline) return BASE_LOOP_DELAY_MS + Random.nextLong(0L, JITTER_MAX_MS)
        if (failures <= 0) return BASE_LOOP_DELAY_MS
        val exponent = failures.coerceAtMost(MAX_BACKOFF_EXPONENT)
        val backoff = (BASE_LOOP_DELAY_MS shl exponent).coerceAtMost(MAX_BACKOFF_DELAY_MS)
        return backoff + Random.nextLong(0L, JITTER_MAX_MS)
    }

    private suspend fun syncNuke(convex: ConvexSyncClient) {
        if (settings.nukeActiveFlow.first()) {
            require(convex.activateNuke()) { "Could not sync Nuke state" }
        }
        val nuke = convex.getNuke() ?: return
        if (nuke.optBoolean("isActive", false)) {
            val startedAt = nuke.optLong("startedAt", System.currentTimeMillis())
            val meditationDoneAt = nuke.optLong("meditationCompletedAt", 0L)
            if (!settings.nukeActiveFlow.first()) settings.setNukeActive(true, startedAt)
            if (meditationDoneAt > 0L && settings.nukeMeditationDoneAtFlow.first() == 0L) {
                settings.setNukeMeditationDone(meditationDoneAt)
            }
        } else if (settings.nukeActiveFlow.first()) {
            settings.clearNuke()
        }
    }

    private suspend fun syncDeviceAndUsage(convex: ConvexSyncClient, updatedAt: Long) {
        val deviceId = getOrCreateDeviceId()
        val usageAccess = UsageTrackerHelper.hasUsageStatsPermission(appContext)
        val trackingStatus = if (usageAccess) "active" else "permission_required"
        val detail = if (usageAccess) null else "Android Usage Access is not granted"
        val deviceErrors = mutableListOf<String>()
        if (!convex.heartbeatDevice(
            deviceId = deviceId,
            name = androidDeviceName(),
            appVersion = appVersion(),
            trackingStatus = trackingStatus,
            statusDetail = detail,
            lastSeen = updatedAt,
        )) {
            deviceErrors += "Could not register this device"
            android.util.Log.w("FocusSyncManager", "heartbeat failed for $deviceId — continuing to usage upload")
        }

        if (!usageAccess) {
            if (deviceErrors.isNotEmpty()) throw IllegalStateException(deviceErrors.joinToString("; "))
            return
        }
        val usage = UsageStatsRepository.getTodaySummary(appContext, maxApps = 100)
        val buckets = usage.topApps.mapNotNull { entry ->
            val seconds = entry.foregroundMinutes * 60L
            if (seconds <= 0L) null else UsageBucket(
                date = today(),
                targetKind = "app",
                targetKey = entry.packageName,
                targetLabel = entry.appName,
                trackedSeconds = seconds,
                updatedAt = updatedAt,
            )
        }
        if (buckets.isNotEmpty()) {
            if (!convex.recordUsageBatch(deviceId, buckets)) {
                deviceErrors += "Could not upload Android usage"
                android.util.Log.w("FocusSyncManager", "usage batch upload failed for $deviceId")
            }
        }
        if (deviceErrors.isNotEmpty()) throw IllegalStateException(deviceErrors.joinToString("; "))
    }

    private fun getOrCreateDeviceId(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
        val fingerprint = sha256("$androidId|${Build.MANUFACTURER}|${Build.MODEL}").take(24)
        val existing = identityPrefs.getString("device_id", null)
        val existingFingerprint = identityPrefs.getString("device_fingerprint", null)
        if (!existing.isNullOrBlank() && existingFingerprint == fingerprint) return existing
        return "android-${UUID.randomUUID()}".also {
            identityPrefs.edit()
                .putString("device_id", it)
                .putString("device_fingerprint", fingerprint)
                .apply()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun androidDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replaceFirstChar { it.uppercase() }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    /**
     * Throttled fallback fetch for user prefs when the snapshot payload does not carry
     * them. Uses `focus:getDashboard` at most once per [PREFS_FETCH_INTERVAL_MS].
     */
    private suspend fun fetchRemotePrefsThrottled(convex: ConvexSyncClient): ConvexSyncClient.Prefs? {
        val now = System.currentTimeMillis()
        val cached = cachedRemotePrefs
        if (cached != null && now - lastPrefsFetchAt < PREFS_FETCH_INTERVAL_MS) return cached
        val fetched = try {
            convex.getPrefs()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("FocusSyncManager", "prefs fallback fetch failed", e)
            null
        }
        if (fetched != null) {
            cachedRemotePrefs = fetched
            lastPrefsFetchAt = now
            return fetched
        }
        return cached
    }

    fun destroy() = scope.cancel()

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    companion object {
        /** Max frequency for the (heavier) getDashboard prefs fallback fetch. */
        private const val PREFS_FETCH_INTERVAL_MS = 5 * 60 * 1000L

        /** Auto-sync loop pacing and failure backoff (audit item 7). */
        private const val BASE_LOOP_DELAY_MS = 30_000L
        private const val MAX_BACKOFF_DELAY_MS = 15 * 60 * 1000L
        private const val MAX_BACKOFF_EXPONENT = 6
        private const val JITTER_MAX_MS = 5_000L

        /**
         * Skew clamp for the hybrid LWW clock (item 3): local writes may never exceed
         * the highest server-seen stamp by more than this, so a fast device clock
         * cannot permanently lock remote writes out of LWW.
         */
        private const val SKEW_CLAMP_MS = 60_000L
    }
}

private fun BlockedApp.toRemote() =
    RemoteApp(packageName, appName, isBlocked, category, specificShortsOnly)

private fun RemoteApp.toLocal() =
    BlockedApp(packageName, appName, isBlocked, category, specificShortsOnly)

private fun BlockedWebsite.toRemote() =
    RemoteSite(domain, displayName, isBlocked, category, isCustom)

private fun RemoteSite.toLocal() =
    BlockedWebsite(domain, displayName, isBlocked, category, isCustom)

private fun TickTickWorkRecord.toRemote() =
    RemoteRecord(id, title, durationMinutes, timestamp, source.name, earnedMinutesCredited, projectName)

private fun RemoteRecord.toLocal() = TickTickWorkRecord(
    id = recordId,
    title = title,
    durationMinutes = durationMinutes,
    timestamp = timestamp,
    source = try { WorkRecordSource.valueOf(source) } catch (_: Exception) { WorkRecordSource.MANUAL_ENTRY },
    earnedMinutesCredited = earnedMinutesCredited,
    projectName = projectName,
)
