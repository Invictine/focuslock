package com.focuslock.app.service

import android.content.Context
import android.util.Log
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogTask
import kotlinx.coroutines.CancellationException

/**
 * Shared entry point for the "eat the frog" feature: open-task cache refresh and the
 * shared block-reason / re-evaluate broadcast constants.
 *
 * [refreshOpenTasks] is deliberately safe to call from broadcast receivers and the
 * accessibility service: network failure is already swallowed inside
 * [TickTickApiClient.fetchOpenTasks] (empty list) and every remaining failure here is
 * logged and reported as `false`, never thrown.
 */
object FrogCoordinator {

    /** Block reason passed to BlockerActivity via [AppMonitorAccessibilityService.EXTRA_BLOCK_REASON]. */
    const val REASON_FROG = "frog"

    /** In-app broadcast telling the accessibility service to re-evaluate now. */
    const val ACTION_FROG_ARMED = FrogWakeReceiver.ACTION_FROG_ARMED

    private const val TAG = "FrogCoordinator"

    /** At most one open-task fetch per this window unless [refreshOpenTasks] is forced. */
    private const val MIN_REFRESH_INTERVAL_MS = 10L * 60L * 1000L

    /** Epoch millis of the last SUCCESSFUL fetch; 0 means "never fetched". */
    @Volatile
    private var lastFetchMillis: Long = 0L

    /**
     * Fetches the user's open TickTick tasks and replaces the picker cache. Returns
     * true only when the cache was actually written; false when skipped by the
     * rate limit or on failure. Never throws (cancellation is rethrown).
     *
     * @param force bypasses the [MIN_REFRESH_INTERVAL_MS] window (picker opened,
     *              open-task cache known to be empty).
     */
    suspend fun refreshOpenTasks(context: Context, force: Boolean = false): Boolean {
        if (!force && System.currentTimeMillis() - lastFetchMillis < MIN_REFRESH_INTERVAL_MS) return false
        return try {
            val tasks = TickTickApiClient().fetchOpenTasks().map { item ->
                FrogTask(
                    id = item.id,
                    title = item.title,
                    projectId = item.projectId,
                    projectName = "",
                    dueDate = item.dueDate ?: "",
                    source = FrogTask.SOURCE_TICKTICK,
                )
            }
            val repository = (context.applicationContext as? FocusLockApplication)?.frogRepository
                ?: FocusLockApplication.instance.frogRepository
            repository.setOpenTaskCache(tasks)
            lastFetchMillis = System.currentTimeMillis()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "open-task refresh failed", e)
            false
        }
    }

    /**
     * Builds an on-device frog task. Id uses the "manual_<epochMillis>" scheme the
     * frog store treats as a stable identity; title is trimmed, project empty.
     */
    fun manualFrog(title: String): FrogTask = FrogTask(
        id = "manual_" + System.currentTimeMillis(),
        title = title.trim(),
        source = FrogTask.SOURCE_MANUAL,
    )
}
