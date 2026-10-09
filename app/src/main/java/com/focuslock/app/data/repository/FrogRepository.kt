package com.focuslock.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogState
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.model.BlockedApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.frogDataStore by preferencesDataStore(name = "focuslock_frog")

/** Thread-safe "yyyy-MM-dd" formatter shared by the pure cycle-date helpers. */
private val FROG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/** Log tag; never log tokens or other secrets. */
private const val FROG_TAG = "FrogRepo"

/**
 * Cycle date of the "eat the frog" day [nowMillis] belongs to.
 *
 * The cycle starts at [wakeHour] local time: before that hour the cycle is still the
 * previous local date, so an early-morning arm attempt belongs to yesterday's (already
 * over) cycle and cannot re-arm. [wakeHour] is clamped to 0..23. Pure function with an
 * explicit [zoneId] so it is unit-testable without Android.
 */
fun frogCycleDate(nowMillis: Long, wakeHour: Int, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val local = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val date = if (local.hour < wakeHour.coerceIn(0, 23)) {
        local.toLocalDate().minusDays(1)
    } else {
        local.toLocalDate()
    }
    return date.format(FROG_DATE_FORMAT)
}

/**
 * The frog lock is active while the feature is enabled, today's frog was armed and the
 * frog is not yet complete — complete means ticked off AND at least [requiredSeconds]
 * tracked. Pure and dependency-free so it can be unit-tested on the JVM.
 */
fun computeFrogLocked(
    enabled: Boolean,
    armed: Boolean,
    tickedOff: Boolean,
    trackedSeconds: Int,
    requiredSeconds: Int,
): Boolean = enabled && armed && !(tickedOff && trackedSeconds >= requiredSeconds)

/** Five-minute local-device grace, anchored to the first interaction in each cycle. */
const val FROG_DAILY_GRACE_MILLIS: Long = 5 * 60 * 1000L

fun frogGraceEndsAt(interactionAtMillis: Long): Long = interactionAtMillis + FROG_DAILY_GRACE_MILLIS

fun frogGraceExpired(interactionAtMillis: Long?, nowMillis: Long): Boolean =
    interactionAtMillis != null && nowMillis >= frogGraceEndsAt(interactionAtMillis)

/** Deadline worth scheduling: only enabled, unarmed state with a future deadline. */
fun pendingFrogGraceDeadline(
    enabled: Boolean,
    armed: Boolean,
    deadlineMillis: Long?,
    nowMillis: Long,
): Long? = deadlineMillis?.takeIf { enabled && !armed && it > nowMillis }

/**
 * Whether the frog may be armed right now: feature enabled, not already armed, at/after
 * [wakeHour] local time, and [cycleDate] already rolled to today's local date (a stale
 * stored cycle date means the lazy rollover has not run yet, so arming is refused).
 * Pure function with an explicit [zoneId] so it is unit-testable without Android.
 */
fun canArmNow(
    nowMillis: Long,
    wakeHour: Int,
    cycleDate: String,
    enabled: Boolean,
    armed: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (!enabled || armed) return false
    val local = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    if (local.hour < wakeHour.coerceIn(0, 23)) return false
    return cycleDate == local.toLocalDate().format(FROG_DATE_FORMAT)
}

/**
 * Whether the stored frog cycle date must be rolled over (wiped + stamped) for
 * [computedCycleDate]. "yyyy-MM-dd" strings sort chronologically, so ONLY a strictly
 * older stored date rolls: an equal date means the stored state is current, and a
 * NEWER stored date (clock moved back, wake hour moved forward) is preserved. That
 * fail-open rule never rolls the cycle backward, so a completed day cannot be wiped
 * and the frog cannot re-lock after a clock change / wake-hour edit.
 */
fun shouldRolloverFrogCycle(storedCycleDate: String?, computedCycleDate: String): Boolean =
    storedCycleDate.orEmpty() < computedCycleDate

/** Keep only canonical Android package names; picker input is persisted across cycles. */
fun sanitizeFrogToolPackages(packages: Set<String>): Set<String> = packages
    .asSequence()
    .map(String::trim)
    .filter { it.length <= 255 && FROG_PACKAGE_NAME.matches(it) }
    .toSet()

private val FROG_PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

/** Both ordinary boundaries (including Shorts-only) and permanent commitments exclude essentials. */
fun frogBoundaryAppPackages(apps: List<BlockedApp>, permanent: Set<String>): Set<String> =
    (permanent + apps.filter { it.isBlocked || it.isPermanent }.map { it.packageName })
        .map { it.trim().lowercase(Locale.ROOT) }.toSet()

private fun frogBoundaryPackages(context: Context): Flow<Set<String>> = combine(
    SettingsRepository(context.applicationContext).blockedAppsFlow,
    PermanentBlocksRepository(context.applicationContext).packagesFlow,
) { apps, permanent ->
    frogBoundaryAppPackages(apps, permanent)
}

/**
 * "Eat the frog" repository backed by its own DataStore file (`focuslock_frog`),
 * following CreditBankRepository's idioms: corruption-hardened read/edit helpers,
 * a lazy daily rollover (same spirit as `checkAndResetDailyStats()`), plain
 * `data.map { ... }` flows, and kotlinx-serialization JSON blobs for lists/tasks.
 *
 * Lock rule: [computeFrogLocked]. The frog is armed once per cycle day (typically by
 * FrogWakeReceiver), a task is selected, and the lock only releases when the task is
 * ticked off with at least the required focus time tracked.
 *
 * No call on this class ever throws (except cancellation): DataStore failures are
 * logged and fall back to defaults, so a caller — including a broadcast receiver —
 * can never crash because of the frog store.
 */
class FrogRepository(
    private val context: Context,
    private val frogStore: DataStore<Preferences> = context.frogDataStore,
    boundaryPackages: Flow<Set<String>> = frogBoundaryPackages(context),
) {

    private val json = Json { ignoreUnknownKeys = true }

    // Async maintenance without blocking collectors (mirrors CreditBankRepository):
    // a failure in a launched rollover must never take down the process.
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(FROG_TAG, "uncaught frog repository maintenance error", e)
        }
    )

    // Serializes the lazy rollover edit so concurrent collectors roll at most once.
    private val rolloverMutex = Mutex()

    val boundaryAppPackagesFlow: Flow<Set<String>> = boundaryPackages
        .map { packages -> packages.map { it.trim().lowercase(Locale.ROOT) }.toSet() }
        .distinctUntilChanged()

    private fun withoutBoundaries(packages: Set<String>, boundaries: Set<String>): Set<String> =
        sanitizeFrogToolPackages(packages).filterNot { it.lowercase(Locale.ROOT) in boundaries }.toSet()

    private fun FrogState.withBoundaries(boundaries: Set<String>): FrogState = copy(
        boundaryAppPackages = boundaries,
        essentialAppPackages = essentialAppPackages?.let { withoutBoundaries(it, boundaries) },
    )

    object Keys {
        val FROG_ENABLED = booleanPreferencesKey("frog_enabled")
        val FROG_REQUIRED_MINUTES = intPreferencesKey("frog_required_minutes")
        val FROG_WAKE_HOUR = intPreferencesKey("frog_wake_hour")
        val FROG_CYCLE_DATE = stringPreferencesKey("frog_cycle_date")
        val FROG_GRACE_STARTED_AT = longPreferencesKey("frog_grace_started_at")
        val FROG_ARMED = booleanPreferencesKey("frog_armed")
        val FROG_SELECTED_JSON = stringPreferencesKey("frog_selected_json")
        val FROG_TICKED_OFF = booleanPreferencesKey("frog_ticked_off")
        val FROG_TRACKED_SECONDS = intPreferencesKey("frog_tracked_seconds")
        val FROG_OPEN_TASKS_JSON = stringPreferencesKey("frog_open_tasks_json")
        val FROG_OPEN_TASKS_FETCHED_AT = longPreferencesKey("frog_open_tasks_fetched_at")
        val FROG_ALLOWED_TOOL_PACKAGES = stringSetPreferencesKey("frog_allowed_tool_packages")
        val FROG_TOOLS_CONFIRMED = booleanPreferencesKey("frog_tools_confirmed")
        val FROG_ESSENTIAL_APP_PACKAGES = stringSetPreferencesKey("frog_essential_app_packages")
    }

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_REQUIRED_MINUTES = 30
        const val DEFAULT_WAKE_HOUR = 5
        const val MIN_REQUIRED_MINUTES = 1
        const val MAX_REQUIRED_MINUTES = 480
        const val MIN_WAKE_HOUR = 0
        const val MAX_WAKE_HOUR = 23
    }

    /** Feature toggle; true by default. */
    val enabledFlow: Flow<Boolean> = frogStore.data
        .map { prefs -> prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(FROG_TAG, "enabledFlow failed; emitting default", e)
            emit(DEFAULT_ENABLED)
        }

    /** Required tracked focus minutes for completion, clamped to 1..480. */
    val requiredMinutesFlow: Flow<Int> = frogStore.data
        .map { prefs -> requiredMinutes(prefs) }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(FROG_TAG, "requiredMinutesFlow failed; emitting default", e)
            emit(DEFAULT_REQUIRED_MINUTES)
        }

    /** Local wake hour (0..23) that starts the frog cycle. */
    val wakeHourFlow: Flow<Int> = frogStore.data
        .map { prefs -> wakeHour(prefs) }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(FROG_TAG, "wakeHourFlow failed; emitting default", e)
            emit(DEFAULT_WAKE_HOUR)
        }

    /** Null means use default launch apps; an empty set is an explicit choice. Boundaries are excluded. */
    val essentialAppPackagesFlow: Flow<Set<String>?> = combine(frogStore.data, boundaryAppPackagesFlow) { prefs, boundaries ->
        prefs[Keys.FROG_ESSENTIAL_APP_PACKAGES]?.let { withoutBoundaries(it, boundaries) }
    }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(FROG_TAG, "essentialAppPackagesFlow failed; emitting defaults", e)
            emit(null)
        }

    /**
     * Derived frog state for the current cycle day. Hot-friendly: the daily rollover is
     * never awaited inside the flow transform — a stale cycle date emits the reset state immediately
     * and persists asynchronously (exactly like CreditBankRepository.statsFlow does for
     * daily stats), while the authoritative reset runs on the next suspend call.
     */
    val frogStateFlow: Flow<FrogState> = combine(frogStore.data, boundaryAppPackagesFlow) { prefs, boundaries ->
        val now = System.currentTimeMillis()
        val today = frogCycleDate(now, wakeHour(prefs))
        val state = if ((prefs[Keys.FROG_CYCLE_DATE] ?: "") != today) {
            // Stale day: emit rolled-over state now, persist off the collector.
            repositoryScope.launch { rolloverIfNeeded(now) }
            rolledOverState(prefs, today)
        } else {
            stateFromPrefs(prefs, today)
        }
        state.withBoundaries(boundaries)
    }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(FROG_TAG, "frogStateFlow failed; emitting default state", e)
            emit(defaultState(System.currentTimeMillis()))
        }

    /** Last cached open-task picker list (survives the daily rollover). */
    val openTasksFlow: Flow<List<FrogTask>> = frogStore.data
        .map { prefs -> decodeOpenTasks(prefs[Keys.FROG_OPEN_TASKS_JSON]) }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(FROG_TAG, "openTasksFlow failed; emitting empty list", e)
            emit(emptyList())
        }

    /** Hot signal for the accessibility service / blocker: is the frog lock on right now? */
    val lockActiveFlow: Flow<Boolean> = frogStateFlow.map { state -> state.locked }

    suspend fun setEnabled(enabled: Boolean) {
        withFrogStore(Unit) {
            rolloverIfNeeded()
            editFrogPrefs { prefs -> prefs[Keys.FROG_ENABLED] = enabled }
        }
    }

    /** Persists the required focus minutes, clamped to [MIN_REQUIRED_MINUTES]..[MAX_REQUIRED_MINUTES]. */
    suspend fun setRequiredMinutes(minutes: Int) {
        withFrogStore(Unit) {
            rolloverIfNeeded()
            editFrogPrefs { prefs ->
                prefs[Keys.FROG_REQUIRED_MINUTES] = minutes.coerceIn(MIN_REQUIRED_MINUTES, MAX_REQUIRED_MINUTES)
            }
        }
    }

    /** Persists the wake hour, clamped to [MIN_WAKE_HOUR]..[MAX_WAKE_HOUR]. */
    suspend fun setWakeHour(hour: Int) {
        withFrogStore(Unit) {
            rolloverIfNeeded()
            editFrogPrefs { prefs -> prefs[Keys.FROG_WAKE_HOUR] = hour.coerceIn(MIN_WAKE_HOUR, MAX_WAKE_HOUR) }
        }
    }

    /** Persist the optional essential launch apps; empty explicitly disables optional defaults. */
    suspend fun setEssentialApps(packages: Set<String>): Boolean = withFrogStore(false) {
        rolloverIfNeeded()
        val boundaries = boundaryAppPackagesFlow.first()
        editFrogPrefs { prefs ->
            prefs[Keys.FROG_ESSENTIAL_APP_PACKAGES] = withoutBoundaries(packages, boundaries)
        }
    }

    /**
     * Records first use for today's configured wake-hour cycle and starts the grace timer.
     * A repeat call only arms after the persisted five-minute deadline.
     */
    suspend fun startGraceIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean = withFrogStore(false) {
        rolloverIfNeeded(nowMillis)
        var armedNow = false
        val committed = editFrogPrefs { prefs ->
            val currentWakeHour = wakeHour(prefs)
            val cycle = prefs[Keys.FROG_CYCLE_DATE].orEmpty()
            val enabled = prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED
            val armed = prefs[Keys.FROG_ARMED] ?: false
            if (!enabled || armed || !canArmNow(nowMillis, currentWakeHour, cycle, enabled, false)) return@editFrogPrefs
            val startedAt = prefs[Keys.FROG_GRACE_STARTED_AT]
            if (startedAt == null) {
                prefs[Keys.FROG_GRACE_STARTED_AT] = nowMillis
            } else if (frogGraceExpired(startedAt, nowMillis)) {
                prefs[Keys.FROG_ARMED] = true
                armedNow = true
            }
        }
        committed && armedNow
    }

    /** Immediate arm primitive retained for controlled setup and test fixtures. */
    suspend fun armIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean = withFrogStore(false) {
        rolloverIfNeeded(nowMillis)
        var armedNow = false
        val committed = editFrogPrefs { prefs ->
            val enabled = prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED
            val armed = prefs[Keys.FROG_ARMED] ?: false
            if (canArmNow(nowMillis, wakeHour(prefs), prefs[Keys.FROG_CYCLE_DATE].orEmpty(), enabled, armed)) {
                prefs[Keys.FROG_ARMED] = true
                armedNow = true
            }
        }
        committed && armedNow
    }

    /** Arms only after a previously started local grace period expires. */
    suspend fun armAfterGraceIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean = withFrogStore(false) {
        rolloverIfNeeded(nowMillis)
        var armedNow = false
        val committed = editFrogPrefs { prefs ->
            val enabled = prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED
            val armed = prefs[Keys.FROG_ARMED] ?: false
            val startedAt = prefs[Keys.FROG_GRACE_STARTED_AT]
            if (enabled && !armed && canArmNow(nowMillis, wakeHour(prefs), prefs[Keys.FROG_CYCLE_DATE].orEmpty(), enabled, armed) && frogGraceExpired(startedAt, nowMillis)) {
                prefs[Keys.FROG_ARMED] = true
                armedNow = true
            }
        }
        committed && armedNow
    }

    /**
     * Selects [task] as today's frog. Switching to a different task resets the tracked
     * progress/tick so a new frog cannot be completed with the previous frog's time;
     * re-selecting the same task is idempotent. Does not arm — call [armIfDue] for that.
     */
    suspend fun selectFrog(task: FrogTask, nowMillis: Long = System.currentTimeMillis()) {
        withFrogStore(Unit) {
            rolloverIfNeeded(nowMillis)
            editFrogPrefs { prefs ->
                val previous = decodeFrog(prefs[Keys.FROG_SELECTED_JSON])
                prefs[Keys.FROG_SELECTED_JSON] = json.encodeToString(task)
                if (previous?.id != task.id) {
                    prefs[Keys.FROG_TICKED_OFF] = false
                    prefs[Keys.FROG_TRACKED_SECONDS] = 0
                    prefs[Keys.FROG_TOOLS_CONFIRMED] = false
                }
            }
        }
    }

    /**
     * Confirms the tools for the current working cycle. Package names are sanitized
     * before persistence. Once confirmed, the selection cannot be changed until the
     * next frog task/cycle is selected.
     */
    suspend fun confirmTools(packages: Set<String>, nowMillis: Long = System.currentTimeMillis()): Boolean =
        withFrogStore(false) {
            rolloverIfNeeded(nowMillis)
            var confirmed = false
            val committed = editFrogPrefs { prefs ->
                val selected = !prefs[Keys.FROG_SELECTED_JSON].isNullOrBlank()
                val armed = prefs[Keys.FROG_ARMED] ?: false
                val phase = FrogPhase.from(
                    armed = armed,
                    selected = selected,
                    tickedOff = prefs[Keys.FROG_TICKED_OFF] ?: false,
                    trackedSeconds = (prefs[Keys.FROG_TRACKED_SECONDS] ?: 0).coerceAtLeast(0),
                    requiredSeconds = requiredSeconds(prefs),
                    toolsConfirmed = prefs[Keys.FROG_TOOLS_CONFIRMED] ?: false,
                )
                if (phase == FrogPhase.PICK_TOOLS) {
                    prefs[Keys.FROG_ALLOWED_TOOL_PACKAGES] = sanitizeFrogToolPackages(packages)
                    prefs[Keys.FROG_TOOLS_CONFIRMED] = true
                    confirmed = true
                }
            }
            committed && confirmed
        }

    /**
     * Clears the selected frog and its progress (tracked seconds + tick), returning the
     * phase to PICK_FROG. Keeps the armed flag and the open-task cache.
     */
    suspend fun clearFrog() {
        withFrogStore(Unit) {
            rolloverIfNeeded()
            editFrogPrefs { prefs ->
                prefs[Keys.FROG_SELECTED_JSON] = ""
                prefs[Keys.FROG_TICKED_OFF] = false
                prefs[Keys.FROG_TRACKED_SECONDS] = 0
                prefs[Keys.FROG_TOOLS_CONFIRMED] = false
            }
        }
    }

    /** Marks the selected frog as done (default) or not done; tracked time is untouched. */
    suspend fun tickOffFrog(tickedOff: Boolean = true) {
        withFrogStore(Unit) {
            rolloverIfNeeded()
            editFrogPrefs { prefs -> prefs[Keys.FROG_TICKED_OFF] = tickedOff }
        }
    }

    /** Overwrites the tracked focus seconds (never negative). */
    suspend fun setTrackedSeconds(seconds: Int) {
        withFrogStore(Unit) {
            rolloverIfNeeded()
            editFrogPrefs { prefs -> prefs[Keys.FROG_TRACKED_SECONDS] = seconds.coerceAtLeast(0) }
        }
    }

    /** Adds tracked focus seconds; ignored when the phase is already COMPLETE. */
    suspend fun addTrackedSeconds(seconds: Int, nowMillis: Long = System.currentTimeMillis()) {
        if (seconds <= 0) return
        withFrogStore(Unit) {
            rolloverIfNeeded(nowMillis)
            editFrogPrefs { prefs ->
                val required = requiredSeconds(prefs)
                val tracked = (prefs[Keys.FROG_TRACKED_SECONDS] ?: 0).coerceAtLeast(0)
                val phase = FrogPhase.from(
                    armed = prefs[Keys.FROG_ARMED] ?: false,
                    selected = !prefs[Keys.FROG_SELECTED_JSON].isNullOrBlank(),
                    tickedOff = prefs[Keys.FROG_TICKED_OFF] ?: false,
                    trackedSeconds = tracked,
                    requiredSeconds = required,
                    toolsConfirmed = prefs[Keys.FROG_TOOLS_CONFIRMED] ?: false,
                )
                if (phase != FrogPhase.COMPLETE) {
                    prefs[Keys.FROG_TRACKED_SECONDS] =
                        (tracked.toLong() + seconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
        }
    }

    /**
     * Replaces the cached open-task picker list and stamps when it was fetched. The cache
     * is intentionally NOT cleared by the daily rollover (still useful for picking).
     */
    suspend fun setOpenTaskCache(tasks: List<FrogTask>, nowMillis: Long = System.currentTimeMillis()) {
        withFrogStore(Unit) {
            rolloverIfNeeded(nowMillis)
            editFrogPrefs { prefs ->
                prefs[Keys.FROG_OPEN_TASKS_JSON] = json.encodeToString(tasks)
                prefs[Keys.FROG_OPEN_TASKS_FETCHED_AT] = nowMillis
            }
        }
    }

    /** Whether the frog lock is active right now (fresh read, rollover applied first). */
    suspend fun isLockActive(nowMillis: Long = System.currentTimeMillis()): Boolean = withFrogStore(false) {
        rolloverIfNeeded(nowMillis)
        val prefs = readFrogPrefs()
        computeFrogLocked(
            enabled = prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED,
            armed = prefs[Keys.FROG_ARMED] ?: false,
            tickedOff = prefs[Keys.FROG_TICKED_OFF] ?: false,
            trackedSeconds = (prefs[Keys.FROG_TRACKED_SECONDS] ?: 0).coerceAtLeast(0),
            requiredSeconds = requiredSeconds(prefs),
        )
    }

    /** Fresh state snapshot for the current cycle day (rollover applied first). */
    suspend fun currentState(nowMillis: Long = System.currentTimeMillis()): FrogState =
        withFrogStore(defaultState(nowMillis)) {
            rolloverIfNeeded(nowMillis)
            val prefs = readFrogPrefs()
            val today = frogCycleDate(nowMillis, wakeHour(prefs))
            val state = if ((prefs[Keys.FROG_CYCLE_DATE] ?: "") != today) {
                rolledOverState(prefs, today)
            } else {
                stateFromPrefs(prefs, today)
            }
            state.withBoundaries(boundaryAppPackagesFlow.first())
        }

    /**
     * Lazy daily rollover (mirrors CreditBankRepository.checkAndResetDailyStats): when the
     * stored cycle date is strictly OLDER than [frogCycleDate] ([shouldRolloverFrogCycle]),
     * stamp the new date and reset the day-scoped state (armed, tick, tracked seconds,
     * selected frog). An equal or newer stored date changes nothing: fail-open preserves a
     * completed day and never rolls the cycle backward after a clock move / wake-hour edit.
     * The open-task cache and fetched-at stamp are kept, since they stay useful for picking.
     */
    private suspend fun rolloverIfNeeded(nowMillis: Long = System.currentTimeMillis()) {
        try {
            val prefs = readFrogPrefs()
            val today = frogCycleDate(nowMillis, wakeHour(prefs))
            if (!shouldRolloverFrogCycle(prefs[Keys.FROG_CYCLE_DATE], today)) return
            rolloverMutex.withLock {
                // Re-check under the mutex: concurrent readers do not all roll.
                val current = readFrogPrefs()
                if (!shouldRolloverFrogCycle(current[Keys.FROG_CYCLE_DATE], today)) return
                editFrogPrefs { stored ->
                    stored[Keys.FROG_CYCLE_DATE] = today
                    stored[Keys.FROG_ARMED] = false
                    stored.remove(Keys.FROG_GRACE_STARTED_AT)
                    stored[Keys.FROG_TICKED_OFF] = false
                    stored[Keys.FROG_TRACKED_SECONDS] = 0
                    stored[Keys.FROG_SELECTED_JSON] = ""
                    stored[Keys.FROG_TOOLS_CONFIRMED] = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(FROG_TAG, "frog rollover failed; keeping current state", e)
        }
    }

    // Corruption-hardened DataStore access (same approach as CreditBankRepository):
    // DataStore throws on a corrupt file, so reads fall back to empty prefs (every
    // caller defaults missing keys) and writes are skipped with a log.
    private suspend fun readFrogPrefs(): Preferences =
        try {
            frogStore.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(FROG_TAG, "frog DataStore read failed; using defaults", e)
            emptyPreferences()
        }

    /** Returns true when the edit committed; false means it was skipped (failure logged). */
    private suspend fun editFrogPrefs(transform: (MutablePreferences) -> Unit): Boolean =
        try {
            frogStore.edit(transform)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(FROG_TAG, "frog DataStore write failed; change skipped", e)
            false
        }

    /**
     * Whole-body guard for every public suspend entry point: a DataStore read/write or
     * decode failure is logged and [fallback] is returned instead of crashing the caller.
     */
    private suspend fun <T> withFrogStore(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(FROG_TAG, "frog DataStore operation failed; using fallback", e)
            fallback
        }

    private fun requiredMinutes(prefs: Preferences): Int =
        (prefs[Keys.FROG_REQUIRED_MINUTES] ?: DEFAULT_REQUIRED_MINUTES)
            .coerceIn(MIN_REQUIRED_MINUTES, MAX_REQUIRED_MINUTES)

    private fun requiredSeconds(prefs: Preferences): Int = requiredMinutes(prefs) * 60

    private fun wakeHour(prefs: Preferences): Int =
        (prefs[Keys.FROG_WAKE_HOUR] ?: DEFAULT_WAKE_HOUR).coerceIn(MIN_WAKE_HOUR, MAX_WAKE_HOUR)

    private fun stateFromPrefs(prefs: Preferences, cycleDate: String): FrogState {
        val enabled = prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED
        val required = requiredSeconds(prefs)
        val frog = decodeFrog(prefs[Keys.FROG_SELECTED_JSON])
        val tickedOff = prefs[Keys.FROG_TICKED_OFF] ?: false
        val tracked = (prefs[Keys.FROG_TRACKED_SECONDS] ?: 0).coerceAtLeast(0)
        val armed = prefs[Keys.FROG_ARMED] ?: false
        return FrogState(
            cycleDate = cycleDate,
            enabled = enabled,
            armed = armed,
            phase = FrogPhase.from(
                armed, frog != null, tickedOff, tracked, required,
                prefs[Keys.FROG_TOOLS_CONFIRMED] ?: false,
            ),
            frog = frog,
            tickedOff = tickedOff,
            trackedSeconds = tracked,
            requiredSeconds = required,
            locked = computeFrogLocked(enabled, armed, tickedOff, tracked, required),
            openTasks = decodeOpenTasks(prefs[Keys.FROG_OPEN_TASKS_JSON]),
            allowedToolPackages = sanitizeFrogToolPackages(prefs[Keys.FROG_ALLOWED_TOOL_PACKAGES] ?: emptySet()),
            toolsConfirmed = prefs[Keys.FROG_TOOLS_CONFIRMED] ?: false,
            essentialAppPackages = decodeEssentialApps(prefs[Keys.FROG_ESSENTIAL_APP_PACKAGES]),
            graceEndsAtMillis = prefs[Keys.FROG_GRACE_STARTED_AT]?.let(::frogGraceEndsAt),
        )
    }

    /** In-memory reset view emitted for a stale cycle day before the persist lands. */
    private fun rolledOverState(prefs: Preferences, cycleDate: String): FrogState {
        val enabled = prefs[Keys.FROG_ENABLED] ?: DEFAULT_ENABLED
        val required = requiredSeconds(prefs)
        return FrogState(
            cycleDate = cycleDate,
            enabled = enabled,
            armed = false,
            phase = FrogPhase.from(
                armed = false,
                selected = false,
                tickedOff = false,
                trackedSeconds = 0,
                requiredSeconds = required,
                toolsConfirmed = false,
            ),
            frog = null,
            tickedOff = false,
            trackedSeconds = 0,
            requiredSeconds = required,
            locked = false,
            openTasks = decodeOpenTasks(prefs[Keys.FROG_OPEN_TASKS_JSON]),
            allowedToolPackages = sanitizeFrogToolPackages(prefs[Keys.FROG_ALLOWED_TOOL_PACKAGES] ?: emptySet()),
            toolsConfirmed = false,
            essentialAppPackages = decodeEssentialApps(prefs[Keys.FROG_ESSENTIAL_APP_PACKAGES]),
            graceEndsAtMillis = null,
        )
    }

    private fun defaultState(nowMillis: Long): FrogState = FrogState(
        cycleDate = frogCycleDate(nowMillis, DEFAULT_WAKE_HOUR),
        enabled = DEFAULT_ENABLED,
        armed = false,
        phase = FrogPhase.NOT_ARMED,
        frog = null,
        tickedOff = false,
        trackedSeconds = 0,
        requiredSeconds = DEFAULT_REQUIRED_MINUTES * 60,
        locked = false,
        openTasks = emptyList(),
        allowedToolPackages = emptySet(),
        toolsConfirmed = false,
        essentialAppPackages = null,
    )

    private fun decodeEssentialApps(packages: Set<String>?): Set<String>? =
        packages?.let(::sanitizeFrogToolPackages)

    private fun decodeFrog(raw: String?): FrogTask? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString<FrogTask>(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeOpenTasks(raw: String?): List<FrogTask> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<FrogTask>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
