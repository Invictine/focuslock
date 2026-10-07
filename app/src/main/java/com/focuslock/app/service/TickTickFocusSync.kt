package com.focuslock.app.service

import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

data class TickTickFocusSyncResult(
    val sessionsFound: Int,
    val newSessions: Int,
    val focusMinutes: Int,
    val earnedMinutes: Int,
)

/** One ingestion path for resume, refresh, Settings and blocker verification. */
class TickTickFocusSync(
    private val settings: SettingsRepository,
    private val bank: CreditBankRepository,
    private val onCreditsChanged: () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun sync(): TickTickFocusSyncResult? = mutex.withLock {
        val api = TickTickApiClient()
        val token = TickTickAuthConfig.getValidAccessToken(settings, api) ?: return@withLock null
        val connection = settings.tickTickConnectionState()
        // Re-read an overlap window: a session may reach TickTick after our last
        // request, or FocusLock may have been offline through midnight. Stable
        // provider IDs make retries safe without a cursor that skips late uploads.
        val now = System.currentTimeMillis()
        val completedSince = LocalDate.now(ZoneId.systemDefault()).minusDays(7)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val records = api.fetchFocusSessions(token, completedSince, now)
        // Do not bank a response from a TickTick account disconnected during the request.
        val latestConnection = settings.tickTickConnectionState()
        if (latestConnection.accountId != connection.accountId ||
            latestConnection.generation != connection.generation ||
            settings.tickTickTokenFlow.first().trim() != token) return@withLock null
        val (count, earned) = bank.recordWorkCreditsDeduped(
            records, settings.workRatioFlow.first(), taskBonusMinutes = 0,
        )
        // LAST_SYNC_TIMESTAMP belongs to the cloud state reconciler. A provider
        // fetch is not evidence that cloud counters were acknowledged.
        bank.setLastTickTickSyncTimestamp(System.currentTimeMillis())
        if (count > 0) onCreditsChanged()
        TickTickFocusSyncResult(records.size, count, records.sumOf { it.durationMinutes }, earned)
    }
}
