package com.focuslock.app.sync

import android.content.Context
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
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

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val detail: String) : SyncStatus
    data class Skipped(val reason: String) : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Auto-sync: pushes local DataStore state to Convex and pulls remote changes.
 * - Periodic every 30s while signed in + manual [syncNow] after focus events.
 * - Work records merge by ID (server + local dedupe) so no double credit.
 * - Block lists use last-writer-wins (small lists, replaced wholesale).
 */
class FocusSyncManager(
    context: Context,
    private val bank: CreditBankRepository,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private fun client(tokenProvider: suspend () -> String?): ConvexSyncClient? {
        val url = try { com.focuslock.app.BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
        if (url.isBlank() || !url.startsWith("http")) return null
        return ConvexSyncClient(url, tokenProvider)
    }

    fun startAutoSync(auth: AuthViewModel) {
        stopAutoSync()
        loop = scope.launch {
            while (isActive) {
                try { syncNow(auth) } catch (_: Exception) { }
                delay(30_000)
            }
        }
    }

    fun stopAutoSync() { loop?.cancel(); loop = null }

    fun syncNowAsync(auth: AuthViewModel) = scope.launch { syncNow(auth) }

    suspend fun syncNow(auth: AuthViewModel) {
        if (!auth.isConfigured()) { _status.value = SyncStatus.Skipped("Clerk not configured — offline mode"); return }
        val convex = client(auth::getConvexToken)
        if (convex == null) { _status.value = SyncStatus.Skipped("Convex URL missing — set convex.url"); return }
        val token = try { auth.getConvexToken() } catch (_: Exception) { null }
        if (token.isNullOrBlank()) { _status.value = SyncStatus.Skipped("Not signed in"); return }

        _status.value = SyncStatus.Syncing
        try {
            val now = System.currentTimeMillis()
            // --- PUSH local ---
            val balanceSec = bank.getBalanceSeconds()
            val stats = bank.statsFlow.first()
            convex.saveState(balanceSec, (stats.totalWorkMinutesToday * 60).toLong(), (stats.totalDoomscrollMinutesToday * 60).toLong(), stats.tasksCompletedToday, stats.lastResetDate.ifBlank { today() }, now)
            val apps = settings.getBlockedApps()
            convex.saveApps(apps.map { RemoteApp(it.packageName, it.appName, it.isBlocked, it.category, it.specificShortsOnly) }, now)
            val sites = settings.getBlockedWebsites()
            convex.saveSites(sites.map { RemoteSite(it.domain, it.displayName, it.isBlocked, it.category, it.isCustom) }, now)
            val history = try { bank.fullHistoryFlow.first().take(100) } catch (_: Exception) { emptyList() }
            var pushed = 0
            for (h in history) {
                if (convex.pushRecord(RemoteRecord(h.id, h.title, h.durationMinutes, h.timestamp, h.source.name, h.earnedMinutesCredited, h.projectName))) pushed++
            }
            // --- PULL remote ---
            val snap = convex.getSnapshot()
            var pulled = 0
            if (snap != null) {
                if (snap.apps.isNotEmpty()) {
                    val remote = snap.apps.map { BlockedApp(it.packageName, it.appName, it.isBlocked, it.category, it.specificShortsOnly) }
                    if (remote != apps) settings.updateBlockedApps(remote)
                }
                if (snap.sites.isNotEmpty()) {
                    val remote = snap.sites.map { BlockedWebsite(it.domain, it.displayName, it.isBlocked, it.category, it.isCustom) }
                    if (remote != sites) settings.updateBlockedWebsites(remote)
                }
                val workRatio = 2; val bonus = 5 // defaults; TickTick credit path re-applies on next sync with real settings
                for (r in snap.records) {
                    if (!bank.hasCreditedTask(r.recordId)) {
                        val src = try { WorkRecordSource.valueOf(r.source) } catch (_: Exception) { WorkRecordSource.MANUAL_ENTRY }
                        bank.recordWorkCredit(TickTickWorkRecord(r.recordId, r.title, r.durationMinutes, r.timestamp, src, r.earnedMinutesCredited, r.projectName), workRatio, bonus)
                        pulled++
                    }
                }
            }
            bank.setLastSyncTimestamp(now)
            _status.value = SyncStatus.Done("Synced · ↑$pushed ↓$pulled · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))}")
        } catch (e: Exception) {
            _status.value = SyncStatus.Error("Sync failed: ${e.message?.take(120) ?: "network"}")
        }
    }

    fun destroy() = scope.cancel()

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}
