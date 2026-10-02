package com.focuslock.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Periodically checks the URL for the browser package currently in the foreground. */
internal class BrowserUrlMonitor(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 1_500L,
    private val check: suspend (String) -> Unit,
) {
    private var watchedPackage: String? = null
    private var monitorJob: Job? = null

    fun watch(packageName: String) {
        if (watchedPackage == packageName && monitorJob?.isActive == true) return

        monitorJob?.cancel()
        watchedPackage = packageName
        monitorJob = scope.launch {
            while (isActive) {
                check(packageName)
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        watchedPackage = null
    }
}
