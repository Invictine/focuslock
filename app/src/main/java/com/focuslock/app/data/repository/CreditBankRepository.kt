package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.UserStats
import com.focuslock.app.data.model.WorkRecordSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

private val Context.bankDataStore by preferencesDataStore(name = "focuslock_bank")

class CreditBankRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    // Thread-safe formatter: the previous shared SimpleDateFormat was mutated from
    // statsFlow.map and IO coroutines concurrently (wrong day strings / parse errors).
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    // Async maintenance without blocking collectors.
    // Default CoroutineExceptionHandler (corruption-hardening): a failure in a
    // launched maintenance job must never take down the process.
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("CreditBank", "uncaught repository maintenance error", e)
        }
    )

    /**
     * Bank-level aggregate-state lock (sync lost-update fix). Every mutator that
     * changes credit state or STATE_UPDATED_AT holds it, and FocusSyncManager takes
     * it around the fresh read that immediately precedes its push/pull decision —
     * so a credit earned mid-sync can never be overwritten by a stale snapshot push.
     * Lock order is always stateMutex → resetMutex / scrollMutex (never the reverse).
     */
    private val stateMutex = Mutex()

    /** Runs [block] while holding [stateMutex]; see the lost-update fix notes above. */
    suspend fun <T> withStateLock(block: suspend () -> T): T = stateMutex.withLock { block() }

    // Corruption-hardened DataStore access: DataStore throws on a corrupt file, and
    // first()/edit() callers previously died with it (process death). Reads fall back
    // to empty prefs (every caller already defaults missing keys to 0/empty) and
    // writes are skipped with a log so the app keeps running until the file recovers.
    private suspend fun readBankPrefs(): Preferences =
        try {
            context.bankDataStore.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CreditBank", "bank DataStore read failed; using defaults", e)
            emptyPreferences()
        }

    /** Returns true when the edit committed; false means it was skipped (failure logged). */
    private suspend fun editBankPrefs(transform: (MutablePreferences) -> Unit): Boolean =
        try {
            context.bankDataStore.edit(transform)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CreditBank", "bank DataStore write failed; change skipped", e)
            false
        }

    // Day boundary = startOfTodayMillis device TZ + date-string counters unified.
    // COUNTING RULE — TASKS_COMPLETED_TODAY counts ONLY real task completions:
    // TickTick completed-today titles (status==2, completedTime in [startOfToday, now))
    // via recordTaskCompletion()/setTasksCompletedToday. Focus/timer/manual minutes
    // NEVER touch it (recordWorkCredit only banks time + history). dueDate/startDate
    // never used (overdue never counts).

    object Keys {
        val CREDIT_BALANCE_SECONDS = longPreferencesKey("credit_balance_seconds")
        val TOTAL_WORK_SECONDS_TODAY = longPreferencesKey("total_work_seconds_today")
        val TOTAL_SCROLL_SECONDS_TODAY = longPreferencesKey("total_scroll_seconds_today")
        val TASKS_COMPLETED_TODAY = intPreferencesKey("tasks_completed_today")
        val LAST_RESET_DATE = stringPreferencesKey("last_reset_date")
        val WORK_HISTORY_JSON = stringPreferencesKey("work_history_json")
        val CREDITED_IDS_JSON = stringPreferencesKey("credited_ids_json")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val STATE_UPDATED_AT = longPreferencesKey("state_updated_at")
        val SYNCED_STATE_UPDATED_AT = longPreferencesKey("synced_state_updated_at")
        // Convex's lifetime Chrome counters acknowledged by this account's bank.
        // Kept as one JSON value so the marker and any corresponding bank adjustment
        // commit in the same DataStore transaction.
        val EXTERNAL_COUNTERS_JSON = stringPreferencesKey("external_counters_json")
    }

    // In-memory fast state for real-time countdown.
    // Synced from DataStore via onEach (no side effects inside map).
    private val _liveBalanceSeconds = MutableStateFlow(0L)
    val liveBalanceSeconds: StateFlow<Long> = _liveBalanceSeconds.asStateFlow()

    @Volatile
    private var balanceLoaded = false

    // Scroll seconds accumulated in memory; persisted in one batched edit every
    // ~30s (or on flushPendingScroll()) instead of a full DataStore edit every 2s.
    @Volatile
    private var pendingScrollSeconds = 0L
    private var lastScrollFlushAt = System.currentTimeMillis()
    private val scrollMutex = Mutex()

    // ONE decode of WORK_HISTORY_JSON feeds workHistoryFlow / fullHistoryFlow /
    // focusMinutesTodayFlow and every write path updates this state eagerly.
    private val _workHistory = MutableStateFlow<List<TickTickWorkRecord>>(emptyList())
    private val historyLoaded = CompletableDeferred<Unit>()

    // Serializes the daily-rollover check so the reset edit runs at most once per day.
    private val resetMutex = Mutex()

    @Volatile
    private var cachedResetDate: String? = null

    init {
        context.bankDataStore.data
            .onEach { prefs ->
                // Serialize with flushPendingScroll's clear-after-write: otherwise this
                // collector could still see the batch marked pending on the emission that
                // already includes its persisted deduction, subtracting it twice.
                scrollMutex.withLock {
                    _liveBalanceSeconds.value = effectiveBalance(prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L)
                }
                balanceLoaded = true
                _workHistory.value = decodeHistory(prefs[Keys.WORK_HISTORY_JSON])
                // DataStore.data never completes, so onCompletion below never fires:
                // latch on the FIRST emission instead, or workHistoryFlow collectors
                // (focus hero, sessions) would wait forever on a fresh install.
                historyLoaded.complete(Unit)
            }
            .onCompletion { historyLoaded.complete(Unit) }
            .catch { e -> android.util.Log.w("CreditBank", "history collector failed", e) }
            .launchIn(repositoryScope)

        // Remove credit awarded by legacy notification heuristics as soon as the
        // repository starts. The explicit reads below repeat this idempotent cleanup
        // so callers never observe the old balance while this job is still queued.
        repositoryScope.launch { removeLegacyNotificationCredits() }
    }

    val statsFlow: Flow<UserStats> = context.bankDataStore.data
        .onEach { prefs ->
            scrollMutex.withLock {
                _liveBalanceSeconds.value = effectiveBalance(prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L)
            }
            balanceLoaded = true
        }
        .map { prefs ->
            val today = todayString()
            val lastDate = prefs[Keys.LAST_RESET_DATE] ?: today
            val balanceSec = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L

            if (lastDate != today) {
                // New day: keep unused credits, reset daily consumption.
                // Counters are persisted lazily by checkAndResetDailyStats on next write.
                // Launch async persist so stale counts don't persist past midnight;
                // keep emitting zeros immediately without suspending the collector.
                repositoryScope.launch { checkAndResetDailyStats() }
                UserStats(
                    creditBalanceMinutes = (balanceSec / 60).toInt(),
                    totalWorkMinutesToday = 0,
                    totalDoomscrollMinutesToday = 0,
                    tasksCompletedToday = 0,
                    lastResetDate = today
                )
            } else {
                UserStats(
                    creditBalanceMinutes = (balanceSec / 60).toInt(),
                    totalWorkMinutesToday = ((prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L) / 60).toInt(),
                    totalDoomscrollMinutesToday = ((prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L) / 60).toInt(),
                    tasksCompletedToday = prefs[Keys.TASKS_COMPLETED_TODAY] ?: 0,
                    lastResetDate = today
                )
            }
        }
        // DataStore corruption fallback (crash fix): surface zeroed stats instead of
        // killing every collector of this flow (the init collector already catches).
        .catch { e ->
            if (e is CancellationException) throw e
            android.util.Log.w("CreditBank", "statsFlow failed; emitting zeroed stats", e)
            emit(UserStats(0, 0, 0, 0, todayString()))
        }

    /** Full recent history (including past days); awaits the one-time warm decode. */
    val fullHistoryFlow: Flow<List<TickTickWorkRecord>> =
        _workHistory.onStart { awaitHistoryLoaded() }

    /** Work history filtered to today so a new day starts clean. */
    val workHistoryFlow: Flow<List<TickTickWorkRecord>> = fullHistoryFlow.map { all ->
        val startOfDay = startOfTodayMillis()
        all.filter { it.timestamp >= startOfDay }
    }

    /** Focus-only minutes today: tasks never count as focus. */
    val focusMinutesTodayFlow: Flow<Int> = workHistoryFlow.map { list ->
        list.filter { isFocusRecord(it.source, it.durationMinutes) }
            .sumOf { it.durationMinutes }
    }

    suspend fun getBalanceSeconds(): Long {
        removeLegacyNotificationCredits()
        checkAndResetDailyStats()
        flushPendingScroll() // no-op when nothing is batched
        val stored = readBankPrefs()[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
        val effective = effectiveBalance(stored)
        _liveBalanceSeconds.value = effective
        balanceLoaded = true
        return effective
    }

    suspend fun hasCreditedTask(taskId: String): Boolean {
        val ids = getCreditedIds()
        return ids.contains(taskId)
    }

    suspend fun getCreditedIds(): Set<String> {
        return try {
            val raw = context.bankDataStore.data.first()[Keys.CREDITED_IDS_JSON]
            if (raw.isNullOrBlank()) emptySet()
            else json.decodeFromString<Set<String>>(raw)
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun getLastSyncTimestamp(): Long {
        return readBankPrefs()[Keys.LAST_SYNC_TIMESTAMP] ?: 0L
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        editBankPrefs { prefs ->
            prefs[Keys.LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    /** Durable account handoff payload; used before another Clerk account opens locally. */
    suspend fun exportAccountState(): JSONObject {
        val p = readBankPrefs()
        return JSONObject().apply {
            put("balance", p[Keys.CREDIT_BALANCE_SECONDS] ?: 0L)
            put("work", p[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L)
            put("scroll", p[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L)
            put("tasks", p[Keys.TASKS_COMPLETED_TODAY] ?: 0)
            put("date", p[Keys.LAST_RESET_DATE] ?: "")
            put("history", p[Keys.WORK_HISTORY_JSON] ?: "[]")
            put("credited", p[Keys.CREDITED_IDS_JSON] ?: "[]")
            put("updatedAt", p[Keys.STATE_UPDATED_AT] ?: 0L)
            put("syncedUpdatedAt", p[Keys.SYNCED_STATE_UPDATED_AT] ?: -1L)
            put("externalCounters", p[Keys.EXTERNAL_COUNTERS_JSON] ?: "")
        }
    }

    suspend fun restoreAccountState(snapshot: JSONObject) {
        stateMutex.withLock {
            editBankPrefs { p ->
                p[Keys.CREDIT_BALANCE_SECONDS] = snapshot.optLong("balance", 0L).coerceAtLeast(0L)
                p[Keys.TOTAL_WORK_SECONDS_TODAY] = snapshot.optLong("work", 0L).coerceAtLeast(0L)
                p[Keys.TOTAL_SCROLL_SECONDS_TODAY] = snapshot.optLong("scroll", 0L).coerceAtLeast(0L)
                p[Keys.TASKS_COMPLETED_TODAY] = snapshot.optInt("tasks", 0).coerceAtLeast(0)
                p[Keys.LAST_RESET_DATE] = snapshot.optString("date", "")
                p[Keys.WORK_HISTORY_JSON] = snapshot.optString("history", "[]")
                p[Keys.CREDITED_IDS_JSON] = snapshot.optString("credited", "[]")
                p[Keys.STATE_UPDATED_AT] = snapshot.optLong("updatedAt", 0L).coerceAtLeast(0L)
                val syncedUpdatedAt = snapshot.optLong("syncedUpdatedAt", -1L)
                if (syncedUpdatedAt >= 0L) p[Keys.SYNCED_STATE_UPDATED_AT] = syncedUpdatedAt
                else p.remove(Keys.SYNCED_STATE_UPDATED_AT)
                val counters = snapshot.optString("externalCounters", "")
                if (counters.isBlank()) p.remove(Keys.EXTERNAL_COUNTERS_JSON)
                else p[Keys.EXTERNAL_COUNTERS_JSON] = counters
            }
        }
        _liveBalanceSeconds.value = snapshot.optLong("balance", 0L).coerceAtLeast(0L)
        _workHistory.value = decodeHistory(snapshot.optString("history", "[]"))
    }

    /** Clears state owned by the signed-in account before another account is opened. */
    suspend fun resetForAccountSwitch() = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            editBankPrefs { prefs ->
                prefs.remove(Keys.CREDIT_BALANCE_SECONDS)
                prefs.remove(Keys.TOTAL_WORK_SECONDS_TODAY)
                prefs.remove(Keys.TOTAL_SCROLL_SECONDS_TODAY)
                prefs.remove(Keys.TASKS_COMPLETED_TODAY)
                prefs.remove(Keys.LAST_RESET_DATE)
                prefs.remove(Keys.WORK_HISTORY_JSON)
                prefs.remove(Keys.CREDITED_IDS_JSON)
                prefs.remove(Keys.LAST_SYNC_TIMESTAMP)
                prefs.remove(Keys.STATE_UPDATED_AT)
                prefs.remove(Keys.SYNCED_STATE_UPDATED_AT)
                prefs.remove(Keys.EXTERNAL_COUNTERS_JSON)
            }
        }
        pendingScrollSeconds = 0L
        _liveBalanceSeconds.value = 0L
        _workHistory.value = emptyList()
        cachedResetDate = null
    }

    suspend fun getStateUpdatedAt(): Long =
        readBankPrefs()[Keys.STATE_UPDATED_AT] ?: 0L

    /** Consistent snapshot of the aggregate bank state (see [withStateLock]). */
    data class BankAggregate(
        val balanceSeconds: Long,
        val workSecondsToday: Long,
        val scrollSecondsToday: Long,
        val tasksCompletedToday: Int,
        val lastResetDate: String,
        val stateUpdatedAt: Long,
        val syncedStateUpdatedAt: Long? = null,
    )

    /** Monotonic server-owned Chrome deltas observed in one focus snapshot. */
    data class ExternalStateCounters(
        val earnedSeconds: Long = 0L,
        val spentSeconds: Long = 0L,
        val date: String = "",
        val workSecondsToday: Long = 0L,
        val scrollSecondsToday: Long = 0L,
        val tasksCompletedToday: Long = 0L,
    ) {
        fun normalized() = copy(
            earnedSeconds = earnedSeconds.coerceAtLeast(0L),
            spentSeconds = spentSeconds.coerceAtLeast(0L),
            workSecondsToday = workSecondsToday.coerceAtLeast(0L),
            scrollSecondsToday = scrollSecondsToday.coerceAtLeast(0L),
            tasksCompletedToday = tasksCompletedToday.coerceAtLeast(0L),
        )

        fun toJson() = JSONObject()
            .put("earned", earnedSeconds).put("spent", spentSeconds).put("date", date)
            .put("work", workSecondsToday).put("scroll", scrollSecondsToday)
            .put("tasks", tasksCompletedToday).toString()
    }

    private fun parseExternalCounters(raw: String?): ExternalStateCounters? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            ExternalStateCounters(
                earnedSeconds = json.optLong("earned", 0L),
                spentSeconds = json.optLong("spent", 0L),
                date = json.optString("date", ""),
                workSecondsToday = json.optLong("work", 0L),
                scrollSecondsToday = json.optLong("scroll", 0L),
                tasksCompletedToday = json.optLong("tasks", 0L),
            ).normalized()
        } catch (_: Exception) { null }
    }

    /**
     * Fresh read of the aggregate state used by the sync push/pull decision
     * (lost-update fix). The caller must hold [withStateLock] so no bank mutator
     * can interleave between this read and the push decision it feeds —
     * see the FocusSyncManager focus-state step.
     */
    suspend fun readAggregateState(): BankAggregate {
        // FocusSyncManager calls this while already holding stateMutex. Use the
        // unlocked helper here to avoid re-entering the non-reentrant mutex.
        removeLegacyNotificationCreditsLocked()
        checkAndResetDailyStats()
        flushPendingScroll() // no-op when nothing is batched
        val prefs = readBankPrefs()
        return BankAggregate(
            balanceSeconds = effectiveBalance(prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L),
            workSecondsToday = prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L,
            scrollSecondsToday = prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L,
            tasksCompletedToday = prefs[Keys.TASKS_COMPLETED_TODAY] ?: 0,
            lastResetDate = prefs[Keys.LAST_RESET_DATE].orEmpty(),
            stateUpdatedAt = prefs[Keys.STATE_UPDATED_AT] ?: 0L,
            syncedStateUpdatedAt = prefs[Keys.SYNCED_STATE_UPDATED_AT],
        )
    }

    /**
     * Replaces aggregate state from a newer server snapshot without awarding credit
     * again. Serialized on [stateMutex] (same lock as the credit mutators) so it can
     * never interleave with a mid-flight [recordWorkCredit].
     *
     * LWW guard (sync lost-update fix): refuses to write when the local state is
     * NEWER than the incoming stamp. A credit recorded while a sync cycle was
     * pushing stamps STATE_UPDATED_AT > the pushed writeTime, so the stale
     * re-stamp is refused here; the next cycle re-pushes the fresh local state.
     *
     * Stale-daily guard (audit item 5): when the remote lastResetDate is older than
     * today, the remote daily counters belong to that older day — applying them
     * would overwrite today's counters and mis-stamp the rollover cache. The
     * balance is not daily and is always applied.
     */
    suspend fun applyRemoteState(
        balanceSeconds: Long,
        workSecondsToday: Long,
        scrollSecondsToday: Long,
        tasksCompletedToday: Int,
        lastResetDate: String,
        updatedAt: Long,
        externalCounters: ExternalStateCounters? = null,
        expectedLocalUpdatedAt: Long? = null,
    ): Boolean {
        val today = todayString()
        val staleDaily = lastResetDate.isNotBlank() && lastResetDate < today
        var applied = false
        stateMutex.withLock {
            val committed = editBankPrefs { prefs ->
                if (expectedLocalUpdatedAt != null &&
                    (prefs[Keys.STATE_UPDATED_AT] ?: 0L) != expectedLocalUpdatedAt
                ) return@editBankPrefs
                if (updatedAt < (prefs[Keys.STATE_UPDATED_AT] ?: 0L)) return@editBankPrefs
                applied = true
                prefs[Keys.CREDIT_BALANCE_SECONDS] = balanceSeconds.coerceAtLeast(0L)
                if (staleDaily) {
                    // Remote daily counters are from an older day: keep today's local
                    // counters when the local day is current, zero them when the local
                    // day is stale too (they are not today's counts either way), and
                    // stamp today so the rollover cache stays consistent.
                    if ((prefs[Keys.LAST_RESET_DATE] ?: today) != today) {
                        prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = 0L
                        prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = 0L
                        prefs[Keys.TASKS_COMPLETED_TODAY] = 0
                    }
                    prefs[Keys.LAST_RESET_DATE] = today
                } else {
                    prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = workSecondsToday.coerceAtLeast(0L)
                    prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = scrollSecondsToday.coerceAtLeast(0L)
                    prefs[Keys.TASKS_COMPLETED_TODAY] = tasksCompletedToday.coerceAtLeast(0)
                    prefs[Keys.LAST_RESET_DATE] = lastResetDate
                }
                prefs[Keys.STATE_UPDATED_AT] = updatedAt
                prefs[Keys.SYNCED_STATE_UPDATED_AT] = updatedAt
                externalCounters?.let { prefs[Keys.EXTERNAL_COUNTERS_JSON] = it.normalized().toJson() }
                _liveBalanceSeconds.value = effectiveBalance(balanceSeconds.coerceAtLeast(0L))
                balanceLoaded = true
            }
            applied = applied && committed
        }
        cachedResetDate = if (staleDaily) today else lastResetDate
        return applied
    }

    /**
     * Merge unacknowledged Chrome counter deltas into a newer local Android aggregate.
     * Counter baseline and bank adjustment are one edit, making retries/restarts safe.
     * Caller can then CAS that returned aggregate against the snapshot it read.
     */
    suspend fun mergeExternalCountersAndRead(
        counters: ExternalStateCounters,
        remoteStateUpdatedAt: Long,
    ): BankAggregate = stateMutex.withLock {
        // This flushes pending scroll before applying external spend and uses the same
        // state -> scroll lock order as normal bank writes.
        val before = readAggregateState()
        val remote = counters.normalized()
        val prefsBefore = readBankPrefs()
        val stored = parseExternalCounters(prefsBefore[Keys.EXTERNAL_COUNTERS_JSON])
        val baseline = if (stored != null) stored else if (
            (prefsBefore[Keys.LAST_SYNC_TIMESTAMP] ?: 0L) > remoteStateUpdatedAt
        ) {
            // Upgrade migration: newer local sync stamp is evidence the old aggregate
            // likely already contains these server counters. Seed without reapplying.
            remote
        } else {
            ExternalStateCounters(date = remote.date)
        }
        val delta = externalCounterDelta(baseline, remote, before.lastResetDate)
        val committed = editBankPrefs { prefs ->
            if (delta.earnedSeconds != 0L || delta.spentSeconds != 0L ||
                delta.workSecondsToday != 0L || delta.scrollSecondsToday != 0L ||
                delta.tasksCompletedToday != 0L) {
                val oldBalance = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
                prefs[Keys.CREDIT_BALANCE_SECONDS] =
                    (oldBalance + delta.earnedSeconds - delta.spentSeconds).coerceAtLeast(0L)
                prefs[Keys.STATE_UPDATED_AT] = maxOf(
                    System.currentTimeMillis(), (prefs[Keys.STATE_UPDATED_AT] ?: 0L) + 1L,
                )
                prefs[Keys.TOTAL_WORK_SECONDS_TODAY] =
                    (prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L) + delta.workSecondsToday
                prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] =
                    (prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L) + delta.scrollSecondsToday
                prefs[Keys.TASKS_COMPLETED_TODAY] =
                    ((prefs[Keys.TASKS_COMPLETED_TODAY] ?: 0).toLong() + delta.tasksCompletedToday)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                _liveBalanceSeconds.value = effectiveBalance(prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L)
                balanceLoaded = true
            }
            prefs[Keys.EXTERNAL_COUNTERS_JSON] = remote.toJson()
        }
        check(committed) { "Could not persist external counter baseline" }
        readAggregateState()
    }

    data class ExternalCounterDelta(
        val earnedSeconds: Long,
        val spentSeconds: Long,
        val workSecondsToday: Long,
        val scrollSecondsToday: Long,
        val tasksCompletedToday: Long,
    )

    /** Merges synced history/IDs only. Aggregate credit state is owned by focusState. */
    suspend fun mergeRemoteWorkRecords(records: List<TickTickWorkRecord>): Int {
        if (records.isEmpty()) return 0
        // Compute the genuinely-new set BEFORE touching DataStore (perf fix): the sync
        // loop calls this every ~30s, and an edit that writes identical state would
        // rewrite disk and re-emit to every collector each cycle. The read is only a
        // fast-path gate; the atomic decode-merge below remains authoritative.
        val knownIds = decodeHistory(readBankPrefs()[Keys.WORK_HISTORY_JSON])
            .asSequence().map { it.id }.toHashSet()
        if (records.none { it.id !in knownIds }) return 0
        var added = 0
        val committed = editBankPrefs { prefs ->
            val current: List<TickTickWorkRecord> = decodeHistory(prefs[Keys.WORK_HISTORY_JSON])
            val currentIds = current.asSequence().map { it.id }.toMutableSet()
            val incoming = records.filter { currentIds.add(it.id) }
            added = incoming.size
            if (incoming.isNotEmpty()) {
                val merged = (incoming + current).sortedByDescending { it.timestamp }.take(50)
                prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(merged)
                val credited: Set<String> = try {
                    prefs[Keys.CREDITED_IDS_JSON]?.let { json.decodeFromString<Set<String>>(it) } ?: emptySet()
                } catch (_: Exception) { emptySet() }
                val updatedCredited: Set<String> = (credited + incoming.map { it.id }).toList().takeLast(500).toSet()
                prefs[Keys.CREDITED_IDS_JSON] = json.encodeToString<Set<String>>(updatedCredited)
                _workHistory.value = merged
            }
        }
        return if (committed) added else 0
    }

    /**
     * Records work credit. Returns earned minutes, or 0 if this task ID was
     * already credited (prevents double-counting on repeated TickTick syncs).
     * Non-focus records (tasks, app-foreground, zero duration) earn 0 and
     * touch neither work seconds nor task counters.
     *
     * COUNTING RULE: focus records bank TIME (balance + work seconds + history)
     * but NEVER increment TASKS_COMPLETED_TODAY — not even titled manual logs.
     * The dashboard tasks card reads TickTick completed-today titles instead,
     * so 0 TickTick tasks shows 0/5 (never 1/5 from manual focus). Real task
     * completions increment the counter only via [recordTaskCompletion].
     */
    suspend fun recordWorkCredit(record: TickTickWorkRecord, workRatio: Int, taskBonusMinutes: Int): Int {
        if (!isFocusRecord(record.source, record.durationMinutes)) {
            return 0
        }

        val totalEarnedMinutes = calculateEarnedMinutes(record.durationMinutes, workRatio, taskBonusMinutes)
        val earnedSeconds = totalEarnedMinutes * 60L

        val enrichedRecord = record.copy(earnedMinutesCredited = totalEarnedMinutes)

        // Dedupe happens INSIDE the DataStore transform: checking a snapshot before
        // edit{} lets two concurrent calls both pass the gate and double-credit.
        // Mirrors recordWorkCreditsDeduped's credited.add(r.id) pattern.
        // stateMutex (lost-update fix): pairs credit writes with the sync decision's
        // fresh read so a concurrently-pushed stale snapshot cannot wipe this credit.
        var credited = false
        stateMutex.withLock {
            checkAndResetDailyStats()
            val committed = editBankPrefs { prefs ->
                val currentIds: MutableSet<String> = try {
                    prefs[Keys.CREDITED_IDS_JSON]?.let { json.decodeFromString<Set<String>>(it) }?.toMutableSet()
                        ?: mutableSetOf()
                } catch (_: Exception) {
                    mutableSetOf()
                }
                // add() is false for IDs already stored OR already credited by a racing call.
                if (!currentIds.add(record.id)) return@editBankPrefs
                credited = true

                val currentBalance = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
                val newBalance = currentBalance + earnedSeconds
                prefs[Keys.CREDIT_BALANCE_SECONDS] = newBalance
                prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
                _liveBalanceSeconds.value = effectiveBalance(newBalance)
                balanceLoaded = true

                val workSec = prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L
                prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = workSec + (record.durationMinutes * 60L)

                // TASKS_COMPLETED_TODAY intentionally NOT incremented here (see COUNTING
                // RULE above): focus minutes are not task completions.

                val updatedHistory = listOf(enrichedRecord) + decodeHistory(prefs[Keys.WORK_HISTORY_JSON]).take(49)
                prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(updatedHistory)
                _workHistory.value = updatedHistory

                prefs[Keys.CREDITED_IDS_JSON] =
                    json.encodeToString<Set<String>>(currentIds.toList().takeLast(500).toSet())
            }
            // Report earnings only when the credit actually persisted.
            if (!committed) credited = false
        }

        // Best-effort "eat the frog" progress: a focus record also advances the day's
        // frog, but ONLY when this call actually committed the credit (deduped/retried
        // records must never add frog minutes without banking credit). Runs OUTSIDE
        // stateMutex so frog DataStore I/O can never slow the credit path;
        // FrogRepository.addTrackedSeconds itself no-ops unless the frog is armed and
        // not yet complete. Never fails this call.
        if (credited && isFocusRecord(record.source, record.durationMinutes) && record.durationMinutes > 0) {
            try {
                FocusLockApplication.instance?.frogRepository?.addTrackedSeconds(record.durationMinutes * 60)
            } catch (_: Throwable) {
            }
        }

        return if (credited) totalEarnedMinutes else 0
    }

    /**
     * Credits a batch of TickTick API records, skipping already-credited IDs.
     * The dedupe set and history are decoded ONCE inside a single atomic edit, so
     * concurrent calls cannot double-credit and there are no per-record transactions.
     * Returns (newRecordsCredited, totalEarnedMinutes).
     */
    suspend fun recordWorkCreditsDeduped(
        records: List<TickTickWorkRecord>,
        workRatio: Int,
        taskBonusMinutes: Int
    ): Pair<Int, Int> {
        if (records.isEmpty()) return Pair(0, 0)

        var totalEarned = 0
        var newCount = 0
        // Focus seconds of the committed batch, for the frog hook below (0 until then).
        var frogSeconds = 0L
        // stateMutex (lost-update fix): same pairing with the sync decision as recordWorkCredit.
        val committed = stateMutex.withLock {
            checkAndResetDailyStats()
            editBankPrefs { prefs ->
                val credited: MutableSet<String> = try {
                    prefs[Keys.CREDITED_IDS_JSON]?.let { json.decodeFromString<Set<String>>(it) }?.toMutableSet()
                        ?: mutableSetOf()
                } catch (_: Exception) {
                    mutableSetOf()
                }
                var history = decodeHistory(prefs[Keys.WORK_HISTORY_JSON])

                val newRecords = mutableListOf<TickTickWorkRecord>()
                var earnedSeconds = 0L
                var workSeconds = 0L
                for (r in records) {
                    if (!isFocusRecord(r.source, r.durationMinutes)) continue
                    // add() is false for IDs already stored OR already credited in this batch.
                    if (!credited.add(r.id)) continue
                    val earned = calculateEarnedMinutes(r.durationMinutes, workRatio, taskBonusMinutes)
                    totalEarned += earned
                    earnedSeconds += earned * 60L
                    workSeconds += r.durationMinutes * 60L
                    newRecords.add(r.copy(earnedMinutesCredited = earned))
                }
                if (newRecords.isEmpty()) return@editBankPrefs

                newCount = newRecords.size
                frogSeconds = workSeconds
                val newBalance = (prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L) + earnedSeconds
                prefs[Keys.CREDIT_BALANCE_SECONDS] = newBalance
                prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
                prefs[Keys.LAST_SYNC_TIMESTAMP] = System.currentTimeMillis()
                prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = (prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L) + workSeconds

                history = (newRecords.asReversed() + history).take(50)
                prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(history)
                prefs[Keys.CREDITED_IDS_JSON] =
                    json.encodeToString<Set<String>>(credited.toList().takeLast(500).toSet())

                _liveBalanceSeconds.value = effectiveBalance(newBalance)
                balanceLoaded = true
                _workHistory.value = history
            }
        }
        // Best-effort "eat the frog" progress, ONLY for a committed batch with new focus
        // records: a deduped/retried batch must never advance the frog without banking
        // credit. Runs OUTSIDE stateMutex; never fails this call.
        if (committed && newCount > 0 && frogSeconds > 0L) {
            try {
                FocusLockApplication.instance?.frogRepository
                    ?.addTrackedSeconds(frogSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } catch (_: Throwable) {
            }
        }
        return Pair(newCount, totalEarned)
    }

    /**
     * Increments TASKS_COMPLETED_TODAY for ONE real task completion only:
     * a TickTick completed-today title (status==2, completedTime in
     * [startOfToday, now)) or a manual titled task log. NEVER call from
     * focus/timer paths — those bank time via recordWorkCredit, which
     * deliberately leaves this counter alone.
     * @return new tasks-completed-today total.
     */
    suspend fun recordTaskCompletion(): Int {
        var updated = 0
        stateMutex.withLock {
            checkAndResetDailyStats()
            editBankPrefs { prefs ->
                updated = (prefs[Keys.TASKS_COMPLETED_TODAY] ?: 0) + 1
                prefs[Keys.TASKS_COMPLETED_TODAY] = updated
                prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
            }
        }
        return updated
    }

    /** Reconciles the counter with an authoritative completed-today count (e.g. TickTick titles size). */
    suspend fun setTasksCompletedToday(count: Int) {
        stateMutex.withLock {
            checkAndResetDailyStats()
            editBankPrefs { prefs ->
                prefs[Keys.TASKS_COMPLETED_TODAY] = count.coerceAtLeast(0)
                prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Decrements the live balance in memory and accumulates the spent seconds.
     * Persistence happens in one batched edit roughly every 30s (or immediately
     * when the balance hits 0), instead of a full DataStore edit every 2s.
     * @return remaining balance seconds (in-memory, never negative).
     */
    suspend fun consumeScrollTime(seconds: Long): Long {
        if (seconds <= 0L) return _liveBalanceSeconds.value
        val (remaining, shouldFlush) = scrollMutex.withLock {
            if (!balanceLoaded) {
                val stored = readBankPrefs()[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
                _liveBalanceSeconds.value = effectiveBalance(stored)
                balanceLoaded = true
            }
            val newBalance = maxOf(0L, _liveBalanceSeconds.value - seconds)
            _liveBalanceSeconds.value = newBalance
            pendingScrollSeconds += seconds
            val flush = pendingScrollSeconds >= SCROLL_FLUSH_THRESHOLD_SECONDS ||
                System.currentTimeMillis() - lastScrollFlushAt >= SCROLL_FLUSH_INTERVAL_MS ||
                newBalance <= 0L
            newBalance to flush
        }
        if (shouldFlush) flushPendingScroll()
        return remaining
    }

    /** Persists batched scroll consumption immediately (app switch / service destroy). */
    suspend fun flushPendingScroll() {
        // Hold scrollMutex for the whole write so concurrent flush callers cannot snapshot the
        // same batch (double deduction) and consumeScrollTime cannot slip seconds into it.
        // Pending is cleared only after DataStore accepts the edit; if the edit throws or is
        // cancelled, the batch stays pending for a later flush (e.g. the NonCancellable
        // onDestroy flush in AppMonitorAccessibilityService) instead of being lost.
        scrollMutex.withLock {
            if (pendingScrollSeconds <= 0L) return
            // Snapshot -> edit -> clear must be atomic w.r.t. cancellation: if the caller is
            // cancelled after the edit commits but before the clear, the batch would survive
            // and the NonCancellable onDestroy flush would deduct it a second time.
            withContext(NonCancellable) {
                val pending = pendingScrollSeconds
                // Pending is cleared only when the edit actually committed; on a failed
                // write the batch stays pending for a later flush instead of being lost.
                if (editBankPrefs { prefs ->
                        val stored = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
                        val updated = maxOf(0L, stored - pending)
                        prefs[Keys.CREDIT_BALANCE_SECONDS] = updated
                        prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = (prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L) + pending
                        prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
                        // `updated` already subtracted this batch: effectiveBalance() would subtract
                        // pendingScrollSeconds (still non-zero here) a second time.
                        _liveBalanceSeconds.value = updated
                    }
                ) {
                    pendingScrollSeconds = 0L
                    lastScrollFlushAt = System.currentTimeMillis()
                }
            }
        }
    }

    /** Audit-named alias for [flushPendingScroll]. */
    suspend fun flushScrollIfDirty() = flushPendingScroll()

    /** Remove only auditable credit previously awarded from unreliable notifications. */
    suspend fun removeLegacyNotificationCredits(): Boolean = stateMutex.withLock {
        removeLegacyNotificationCreditsLocked()
    }

    /** Caller must hold [stateMutex]. */
    private suspend fun removeLegacyNotificationCreditsLocked(): Boolean {
        val before = readBankPrefs()
        val preview = cleanLegacyNotificationCredits(
            history = decodeHistory(before[Keys.WORK_HISTORY_JSON]),
            balanceSeconds = before[Keys.CREDIT_BALANCE_SECONDS] ?: 0L,
            workSecondsToday = before[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L,
            lastResetDate = before[Keys.LAST_RESET_DATE].orEmpty(),
            today = todayString(),
        )
        // getBalanceSeconds() is called by the active countdown; avoid a DataStore
        // edit transaction on every poll once legacy rows have been cleaned.
        if (!preview.changed) return false

        var changed = false
        val committed = editBankPrefs { prefs ->
            val history = decodeHistory(prefs[Keys.WORK_HISTORY_JSON])
            val balance = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
            val workSeconds = prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L
            val resetDate = prefs[Keys.LAST_RESET_DATE].orEmpty()
            val today = todayString()
            val cleanup = cleanLegacyNotificationCredits(
                history = history,
                balanceSeconds = balance,
                workSecondsToday = workSeconds,
                lastResetDate = resetDate,
                today = today,
            )
            if (!cleanup.changed) return@editBankPrefs

            changed = true
            prefs[Keys.CREDIT_BALANCE_SECONDS] = cleanup.balanceSeconds
            prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = cleanup.workSecondsToday
            prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(cleanup.history)
            prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
            _liveBalanceSeconds.value = effectiveBalance(cleanup.balanceSeconds)
            _workHistory.value = cleanup.history
            balanceLoaded = true
        }
        return committed && changed
    }

    /** Backward-compatible entry point; only evidence-backed legacy awards are removed. */
    suspend fun reconcileBalanceWithFocus(): Boolean = removeLegacyNotificationCredits()

    /** Debug/test reset: clears today counters + history AND balance (0 work = 0 bank). */
    suspend fun resetTodayCounters() {
        scrollMutex.withLock {
            pendingScrollSeconds = 0L
            lastScrollFlushAt = 0L
        }
        stateMutex.withLock {
            editBankPrefs { prefs ->
                prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = 0L
                prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = 0L
                prefs[Keys.TASKS_COMPLETED_TODAY] = 0
                prefs[Keys.CREDIT_BALANCE_SECONDS] = 0L
                prefs[Keys.STATE_UPDATED_AT] = System.currentTimeMillis()
                _liveBalanceSeconds.value = 0L
                prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(emptyList<TickTickWorkRecord>())
                _workHistory.value = emptyList()
            }
        }
    }

    /**
     * Writes daily counters only when the stored reset date rolled over.
     * Fast path: when the date is already known/matching, neither a read nor an
     * edit happens; the previous implementation always ran a DataStore edit.
     */
    private suspend fun checkAndResetDailyStats() {
        val today = todayString()
        if (cachedResetDate == today) return
        resetMutex.withLock {
            if (cachedResetDate == today) return
            val storedDate = readBankPrefs()[Keys.LAST_RESET_DATE]
            if (storedDate == today) {
                cachedResetDate = today
                return
            }
            // Cache the rolled-over date only when the reset edit committed; on a
            // failed write the check re-runs on the next call (stale counters do not
            // silently survive as "already reset").
            if (editBankPrefs { prefs ->
                val lastReset = prefs[Keys.LAST_RESET_DATE] ?: today
                if (lastReset != today) {
                    prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = 0L
                    prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = 0L
                    prefs[Keys.TASKS_COMPLETED_TODAY] = 0
                    prefs[Keys.LAST_RESET_DATE] = today
                    prefs[Keys.STATE_UPDATED_AT] = maxOf(
                        System.currentTimeMillis(), (prefs[Keys.STATE_UPDATED_AT] ?: 0L) + 1L,
                    )
                    // Prune history older than 7 days to bound storage
                    val raw = prefs[Keys.WORK_HISTORY_JSON]
                    if (!raw.isNullOrBlank()) {
                        val all = decodeHistory(raw)
                        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                        val pruned = all.filter { it.timestamp >= cutoff }.take(50)
                        prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(pruned)
                        _workHistory.value = pruned
                    }
                }
            }) {
                cachedResetDate = today
            }
        }
    }

    private fun todayString(): String = LocalDate.now().format(dateFormat)

    private fun effectiveBalance(stored: Long): Long = maxOf(0L, stored - pendingScrollSeconds)

    private fun decodeHistory(raw: String?): List<TickTickWorkRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun awaitHistoryLoaded() {
        if (!historyLoaded.isCompleted) historyLoaded.await()
    }

    companion object {
        private const val SCROLL_FLUSH_THRESHOLD_SECONDS = 30L
        private const val SCROLL_FLUSH_INTERVAL_MS = 30_000L

        /** Pure positive-delta merge; a date change resets only the external daily baseline. */
        internal fun externalCounterDelta(
            baseline: ExternalStateCounters,
            current: ExternalStateCounters,
            localDate: String,
        ): ExternalCounterDelta {
            val next = current.normalized()
            val old = baseline.normalized()
            val sameExternalDay = old.date == next.date
            val dailyApplies = next.date.isNotBlank() && next.date == localDate
            return ExternalCounterDelta(
                earnedSeconds = (next.earnedSeconds - old.earnedSeconds).coerceAtLeast(0L),
                spentSeconds = (next.spentSeconds - old.spentSeconds).coerceAtLeast(0L),
                workSecondsToday = if (dailyApplies) (next.workSecondsToday - if (sameExternalDay) old.workSecondsToday else 0L).coerceAtLeast(0L) else 0L,
                scrollSecondsToday = if (dailyApplies) (next.scrollSecondsToday - if (sameExternalDay) old.scrollSecondsToday else 0L).coerceAtLeast(0L) else 0L,
                tasksCompletedToday = if (dailyApplies) (next.tasksCompletedToday - if (sameExternalDay) old.tasksCompletedToday else 0L).coerceAtLeast(0L) else 0L,
            )
        }

        /** Explicit focus logs and completed API focus sessions count; tasks/notifications do not. */
        fun isFocusRecord(source: WorkRecordSource, durationMinutes: Int) =
            (source == WorkRecordSource.MANUAL_ENTRY || source == WorkRecordSource.TICKTICK_FOCUS_API) &&
                durationMinutes > 0

        internal data class LegacyNotificationCleanup(
            val history: List<TickTickWorkRecord>,
            val balanceSeconds: Long,
            val workSecondsToday: Long,
            val changed: Boolean,
        )

        /** Pure, idempotent migration logic; legacy rows remain visible for audit. */
        internal fun cleanLegacyNotificationCredits(
            history: List<TickTickWorkRecord>,
            balanceSeconds: Long,
            workSecondsToday: Long,
            lastResetDate: String,
            today: String,
        ): LegacyNotificationCleanup {
            val invalidRows = history.filter {
                it.source == WorkRecordSource.TICKTICK_NOTIFICATION && it.earnedMinutesCredited > 0
            }
            if (invalidRows.isEmpty()) {
                return LegacyNotificationCleanup(history, balanceSeconds, workSecondsToday, changed = false)
            }

            // A duplicated legacy row for one ID could only have been credited once.
            val distinctAwards = invalidRows.distinctBy { it.id }
            val earnedSeconds = distinctAwards.sumOf { it.earnedMinutesCredited.toLong() * 60L }
            val workToRemove = if (lastResetDate.isBlank()) {
                // Legacy installs may have a missing date while their current counters
                // still include the notification batch; remove the known matching work.
                distinctAwards.sumOf { it.durationMinutes.coerceAtLeast(0).toLong() * 60L }
            } else if (lastResetDate == today) {
                distinctAwards.filter { dateString(it.timestamp) == lastResetDate }
                    .sumOf { it.durationMinutes.coerceAtLeast(0).toLong() * 60L }
            } else {
                0L
            }
            val cleanedHistory = history.map {
                if (it.source == WorkRecordSource.TICKTICK_NOTIFICATION && it.earnedMinutesCredited > 0) {
                    it.copy(earnedMinutesCredited = 0)
                } else it
            }
            return LegacyNotificationCleanup(
                history = cleanedHistory,
                balanceSeconds = (balanceSeconds - earnedSeconds).coerceAtLeast(0L),
                workSecondsToday = (workSecondsToday - workToRemove).coerceAtLeast(0L),
                changed = true,
            )
        }

        private fun dateString(timestamp: Long): String =
            java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))

        /** Pure work→leisure conversion. Extracted for unit testing. */
        fun calculateEarnedMinutes(workMinutes: Int, workRatio: Int, taskBonusMinutes: Int): Int {
            val earnedFromTime = if (workRatio > 0) workMinutes / workRatio else workMinutes
            val earnedFromBonus = if (workMinutes > 0) taskBonusMinutes else 0
            return maxOf(1, earnedFromTime + earnedFromBonus)
        }

        fun startOfTodayMillis(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
