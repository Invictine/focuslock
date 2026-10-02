package com.focuslock.app.service

import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val records = api.fetchFocusSessionsToday(token)
        // Do not bank a response from a TickTick account disconnected during the request.
        if (settings.tickTickTokenFlow.first().trim() != token) return@withLock null
        val (count, earned) = bank.recordWorkCreditsDeduped(
            records, settings.workRatioFlow.first(), taskBonusMinutes = 0,
        )
        bank.setLastSyncTimestamp(System.currentTimeMillis())
        if (count > 0) onCreditsChanged()
        TickTickFocusSyncResult(records.size, count, records.sumOf { it.durationMinutes }, earned)
    }
}
