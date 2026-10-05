package com.focuslock.app.data.repository

import kotlinx.coroutines.CancellationException

/** Keep caller cancellation observable while treating unavailable automation state as unlocked. */
internal suspend fun isStrictAutomationActive(check: suspend () -> Boolean): Boolean = try {
    check()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}
