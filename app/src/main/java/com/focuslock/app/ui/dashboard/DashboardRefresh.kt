package com.focuslock.app.ui.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Refreshes local dashboard sources together, then uploads their resulting account state. */
internal suspend fun refreshDashboardSources(
    refreshTasks: suspend () -> Boolean,
    refreshFocus: suspend () -> Boolean,
    refreshAccount: suspend () -> Boolean,
): List<String> = coroutineScope {
    suspend fun refresh(label: String, action: suspend () -> Boolean): String? = try {
        if (action()) null else label
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        label
    }

    val tasks = async { refresh("TickTick tasks", refreshTasks) }
    val focus = async { refresh("TickTick focus", refreshFocus) }

    // Keep a stable report order regardless of which concurrent refresh finishes first.
    val failures = listOfNotNull(tasks.await(), focus.await()).toMutableList()
    refresh("account data", refreshAccount)?.let(failures::add)
    failures
}
