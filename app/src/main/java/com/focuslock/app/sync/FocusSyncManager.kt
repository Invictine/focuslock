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
import com.focuslock.app.data.repository.TargetGroup as LocalTargetGroup
import com.focuslock.app.data.repository.TargetGroupMember as LocalTargetGroupMember
import com.focuslock.app.data.repository.TargetGroupsRepository
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
    private val targetGroups: TargetGroupsRepository,
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

    /**
     * Today's combined tracked seconds per group (`groupId -> trackedSeconds`), refreshed
     * from `usage:getUsageSummary(today..today)` once per sync cycle. The accessibility
     * service reads it synchronously on the enforcement hot path, so it deliberately never
     * blocks; it can lag ~30s and it is not cleared when a fetch fails (stale is
     * acceptable, but treating an outage as 0 would under-block).
     */
    private val _groupUsageTodaySeconds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val groupUsageTodaySeconds: StateFlow<Map<String, Long>> = _groupUsageTodaySeconds.asStateFlow()

    /**
     * All-time, all-device target catalog (`usage:listKnownTargets`), refreshed once per
     * sync cycle. The merge picker unions this with its local app/website sources so a
     * target that only ever existed on another device (a Windows exe, an extension-only
     * domain) is still selectable. Empty until the first successful fetch and deliberately
     * never cleared on a failed one: a remote-only candidate is lost for good if a
     * transient error empties the list.
     */
    private val _knownTargets = MutableStateFlow<List<KnownTarget>>(emptyList())
    val knownTargets: StateFlow<List<KnownTarget>> = _knownTargets.asStateFlow()

    /** `"kind:key"` -> target over the last successful catalog fetch; empty until then. */
    @Volatile
    private var knownTargetIndex: Map<String, KnownTarget> = emptyMap()

    /**
     * Non-suspending lookup over the last successful catalog fetch. Website keys are
     * matched with the repository's normalization (lowercase, leading `www.` stripped)
     * so a locally-typed `www.foo.com` finds the stored `foo.com` row.
     */
    fun knownTargetFor(targetKind: String, targetKey: String): KnownTarget? {
        val kind = targetKind.trim().lowercase()
        var key = targetKey.trim().lowercase()
        if (kind.isEmpty() || key.isEmpty()) return null
        if (kind == "website") key = key.removePrefix("www.")
        if (key.isEmpty()) return null
        return knownTargetIndex["$kind:$key"]
    }

    /** Total tracked seconds across all targets today (same summary pull); 0 until first fetch. */
    @Volatile
    var totalTrackedSecondsToday: Long = 0L
        private set

    /** Non-suspending hot-path read: today's combined seconds for one group, 0 when unknown. */
    fun groupUsageTodaySecondsFor(groupId: String): Long =
        _groupUsageTodaySeconds.value[groupId] ?: 0L

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
            if (snapshot.appsUpdatedAt > localAppsUpdatedAt) {
                settings.applyRemoteBlockedApps(snapshot.apps.map(RemoteApp::toLocal), snapshot.appsUpdatedAt)
                pulled++
            } else if (snapshot.appsUpdatedAt == 0L || localAppsUpdatedAt > snapshot.appsUpdatedAt) {
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
            if (snapshot.sitesUpdatedAt > localSitesUpdatedAt) {
                settings.applyRemoteBlockedWebsites(snapshot.sites.map(RemoteSite::toLocal), snapshot.sitesUpdatedAt)
                pulled++
            } else if (snapshot.sitesUpdatedAt == 0L || localSitesUpdatedAt > snapshot.sitesUpdatedAt) {
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
                // Merged target groups: full-replace LWW (same shape as boundaries) plus
                // the per-group usage cache for the accessibility enforcement fast path.
                val groupsOutcome = syncTargetGroups(convex, startedAt)
                pulled += groupsOutcome.pulled
                pushed += groupsOutcome.pushed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "target-groups sync step failed", e)
                stepErrors += "target groups: ${e.message?.take(120) ?: "network"}"
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

            try {
                // Known-target catalog for the merge picker: once per cycle. Its own
                // failure domain — a catalog hiccup is UI-only, so it never reaches
                // stepErrors (which would trip the sync backoff).
                refreshKnownTargets(convex)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("FocusSyncManager", "known-targets refresh failed", e)
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

    private data class GroupsSyncOutcome(val pulled: Int, val pushed: Int)

    /**
     * Merged target groups + today's per-group usage cache.
     *
     * Groups sync exactly like blocked websites: full-replace LWW on an independent
     * clock ([TargetGroupsRepository.Keys.TARGET_GROUPS_UPDATED_AT]), but the pull
     * endpoint is `groups:groupsState`, which returns the authoritative collection
     * version even when the list is empty. A failed read returns null and the whole
     * group step is skipped: pushing on a failed read is what used to resurrect
     * deleted groups and clobber newer remote lists.
     *
     * Decision table (remote = server state, local = this device):
     * - remote.updatedAt > localUpdatedAt → pull: `replaceAll(remote.groups, remote.updatedAt)`.
     *   An empty remote list with a newer version is a real "deleted everywhere": it
     *   clears local groups and persists the new version.
     * - remote.updatedAt < localUpdatedAt → push local. Persist/re-stamp the local clock
     *   only when the server accepted (`applied == true`). On rejection the server has
     *   newer data, so re-read and apply the pull rule; a failed mutation leaves both
     *   sides untouched and the next cycle retries.
     * - remote.updatedAt == localUpdatedAt → normally nothing, but if the normalized
     *   lists differ, adopt the server list (source of truth) to avoid permanent
     *   divergence.
     *
     * Fresh-install invariant: a new device starts with TARGET_GROUPS_UPDATED_AT == 0
     * and zero local groups. If the server ever had groups its version is > 0, so the
     * pull branch wins; if the server version is also 0 the server list is empty too,
     * so the push branch (remote < local) is unreachable and a fresh install can never
     * push an empty list over remote groups.
     *
     * The usage pull is wrapped in its own try so a summary hiccup can never fail the
     * group sync or the cycle; on failure the previous cache is kept deliberately.
     */
    private suspend fun syncTargetGroups(convex: ConvexSyncClient, startedAt: Long): GroupsSyncOutcome {
        var pulled = 0
        var pushed = 0
        val localGroups = targetGroups.currentGroups()
        val localUpdatedAt = targetGroups.getUpdatedAt()

        val remote = convex.groupsState()
        if (remote == null) {
            // Blank token, network error, HTTP failure or malformed payload: the server
            // state is unknown, so never push local data over it.
            android.util.Log.w("FocusSyncManager", "target-groups state unavailable; skipping group pull/push this cycle")
        } else {
            when {
                remote.updatedAt > localUpdatedAt -> {
                    // Pull wins, including the delete-everywhere case (empty list with a
                    // newer clock): replaceAll(emptyList(), newerVersion) must clear local
                    // groups while persisting the new version.
                    targetGroups.replaceAll(remote.groups.map { it.toLocal() }, remote.updatedAt)
                    pulled++
                }
                remote.updatedAt < localUpdatedAt -> {
                    val writeTime = clampedWriteTime(startedAt, localUpdatedAt)
                    val result = convex.saveGroups(localGroups.map { it.toRemote() }, writeTime)
                    if (result.applied) {
                        // Re-stamp locally so the pushed clock matches the server and the
                        // next cycle does not re-push the same list.
                        targetGroups.replaceAll(localGroups, writeTime)
                        pushed++
                    } else {
                        // Rejected (the server has a newer version) or the mutation failed.
                        // Re-read and apply the pull rule; never re-stamp on a rejection.
                        val latest = convex.groupsState()
                        when {
                            latest == null -> android.util.Log.w(
                                "FocusSyncManager",
                                "target-groups push not applied and the re-read failed; keeping local state",
                            )
                            latest.updatedAt > localUpdatedAt -> {
                                targetGroups.replaceAll(latest.groups.map { it.toLocal() }, latest.updatedAt)
                                pulled++
                            }
                            latest.updatedAt == localUpdatedAt &&
                                !groupsEquivalent(localGroups, latest.groups) -> {
                                // Equal clocks but divergent content: server wins.
                                targetGroups.replaceAll(latest.groups.map { it.toLocal() }, latest.updatedAt)
                                pulled++
                            }
                            else -> android.util.Log.w(
                                "FocusSyncManager",
                                "target-groups push not applied and remote is not newer; will retry next cycle",
                            )
                        }
                    }
                }
                else -> {
                    // Same version: normally nothing to do, but if the normalized content
                    // differs, adopting the server list avoids permanent divergence.
                    if (!groupsEquivalent(localGroups, remote.groups)) {
                        targetGroups.replaceAll(remote.groups.map { it.toLocal() }, remote.updatedAt)
                        pulled++
                    }
                }
            }
        }

        try {
            val summary = convex.getUsageSummary(fromDate = today(), toDate = today())
            if (summary != null) {
                val byGroup = HashMap<String, Long>(summary.groups.size * 2)
                for (group in summary.groups) {
                    byGroup[group.groupId] = group.trackedSeconds.coerceAtLeast(0L)
                }
                for (target in summary.groupedTargets) {
                    val groupId = target.groupId ?: continue
                    byGroup.putIfAbsent(groupId, target.trackedSeconds.coerceAtLeast(0L))
                }
                _groupUsageTodaySeconds.value = byGroup
                totalTrackedSecondsToday = summary.totalTrackedSeconds.coerceAtLeast(0L)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("FocusSyncManager", "group usage summary fetch failed", e)
        }
        return GroupsSyncOutcome(pulled, pushed)
    }

    /**
     * Normalization-tolerant comparison for the equal-version tie-break: groupId, name,
     * merged limit and the sorted member key set, ignoring list/member order and case.
     * Raw equality would report divergence every cycle whenever the server canonicalizes
     * (member order, `www.`-stripped website keys, clamped limits).
     */
    private fun groupsEquivalent(local: List<LocalTargetGroup>, remote: List<TargetGroup>): Boolean =
        local.map { groupSignature(it.groupId, it.name, it.dailyLimitMinutes, it.members.map { m -> m.targetKind to m.targetKey }) }
            .sorted() ==
            remote.map { groupSignature(it.groupId, it.name, it.dailyLimitMinutes, it.members.map { m -> m.targetKind to m.targetKey }) }
                .sorted()

    private fun groupSignature(
        groupId: String,
        name: String,
        limitMinutes: Int?,
        memberKeys: List<Pair<String, String>>,
    ): String = buildString {
        append(groupId.trim())
        append('\u0000')
        append(name.trim())
        append('\u0000')
        append(limitMinutes ?: 0)
        append('\u0000')
        memberKeys
            .map { (kind, key) -> "${kind.trim().lowercase()}:${key.trim().lowercase()}" }
            .sorted()
            .joinTo(this, ",")
    }

    /**
     * Refreshes the all-device target catalog used by the merge picker. A null result
     * means "server state unknown" (blank token, network error, malformed payload), so
     * the previous catalog is kept — a failed fetch must never look like "no targets".
     * [ConvexSyncClient.listKnownTargets] already swallows non-cancellation failures.
     */
    private suspend fun refreshKnownTargets(convex: ConvexSyncClient) {
        val fetched = convex.listKnownTargets()
        if (fetched == null) {
            android.util.Log.w("FocusSyncManager", "known-targets fetch unavailable; keeping the previous catalog")
            return
        }
        _knownTargets.value = fetched
        val index = HashMap<String, KnownTarget>(fetched.size * 2)
        for (target in fetched) {
            val kind = target.targetKind.trim().lowercase()
            var key = target.targetKey.trim().lowercase()
            if (kind == "website") key = key.removePrefix("www.")
            if (kind.isEmpty() || key.isEmpty()) continue
            index.putIfAbsent("$kind:$key", target)
        }
        knownTargetIndex = index
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

// Server group -> local group. Keys are lowercased here as well as in the repository
// (defense in depth; the server already canonicalizes them).
private fun TargetGroup.toLocal() = LocalTargetGroup(
    groupId = groupId,
    name = name,
    category = category,
    members = members.map { it.toLocal() },
    dailyLimitMinutes = dailyLimitMinutes,
    limitEnabled = limitEnabled ?: true,
    updatedAt = updatedAt,
)

private fun TargetGroupMember.toLocal() = LocalTargetGroupMember(
    targetKind = targetKind.trim().lowercase(),
    targetKey = targetKey.trim().lowercase(),
    targetLabel = targetLabel,
)

private fun LocalTargetGroup.toRemote() = TargetGroup(
    groupId = groupId,
    name = name,
    category = category,
    members = members.map { it.toRemote() },
    dailyLimitMinutes = dailyLimitMinutes,
    limitEnabled = limitEnabled,
    updatedAt = updatedAt,
)

private fun LocalTargetGroupMember.toRemote() = TargetGroupMember(
    targetKind = targetKind,
    targetKey = targetKey,
    targetLabel = targetLabel,
)
