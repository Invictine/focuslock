package com.focuslock.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserUrlMonitorTest {

    @Test
    fun checksPeriodicallyEvenWhenNoUrlEventsArrive() = runTest {
        val checks = mutableListOf<String>()
        val monitor = BrowserUrlMonitor(backgroundScope, intervalMs = 1_500L) { checks += it }

        monitor.watch("browser")
        runCurrent()
        advanceTimeBy(1_500L)
        runCurrent()
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(listOf("browser", "browser", "browser"), checks)
        monitor.stop()
    }

    @Test
    fun repeatedWatchForSamePackageDoesNotPostponeChecks() = runTest {
        val checksAt = mutableListOf<Long>()
        val monitor = BrowserUrlMonitor(backgroundScope, intervalMs = 1_500L) {
            checksAt += testScheduler.currentTime
        }

        monitor.watch("browser")
        runCurrent()
        advanceTimeBy(1_000L)
        monitor.watch("browser")
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(listOf(0L, 1_500L), checksAt)
        monitor.stop()
    }

    @Test
    fun switchingPackageChecksNewPackageImmediatelyAndStopsOldChecks() = runTest {
        val checks = mutableListOf<Pair<Long, String>>()
        val monitor = BrowserUrlMonitor(backgroundScope, intervalMs = 1_500L) {
            checks += testScheduler.currentTime to it
        }

        monitor.watch("first")
        runCurrent()
        advanceTimeBy(700L)
        monitor.watch("second")
        runCurrent()
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(listOf(0L to "first", 700L to "second", 2_200L to "second"), checks)
        monitor.stop()
    }

    @Test
    fun stopCancelsChecksAndAllowsFreshWatch() = runTest {
        val checks = mutableListOf<String>()
        val monitor = BrowserUrlMonitor(backgroundScope, intervalMs = 1_500L) { checks += it }

        monitor.watch("first")
        runCurrent()
        monitor.stop()
        advanceTimeBy(3_000L)
        runCurrent()
        monitor.watch("second")
        runCurrent()

        assertEquals(listOf("first", "second"), checks)
        monitor.stop()
    }

    @Test
    fun transientMissingUrlIsRetriedAndBlockedWithoutAnotherWatchEvent() = runTest {
        val extractedUrls = ArrayDeque<String?>().apply {
            addLast(null)
            addLast("https://x.com/path")
        }
        val blocked = mutableListOf<String>()
        val monitor = BrowserUrlMonitor(backgroundScope, intervalMs = 1_500L) {
            val url = extractedUrls.removeFirst()
            if (url?.contains("x.com") == true) blocked += url
        }

        monitor.watch("browser")
        runCurrent()
        assertEquals(emptyList<String>(), blocked)

        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(listOf("https://x.com/path"), blocked)
        monitor.stop()
    }

    @Test
    fun switchingBrowserCancelsSuspendedExtractionAndIgnoresOldResult() = runTest {
        val results = mutableListOf<String>()
        val monitor = BrowserUrlMonitor(backgroundScope, intervalMs = 1_500L) { packageName ->
            if (packageName == "first") {
                delay(1_000L)
                results += "first:x.com"
            } else {
                results += "second:allowed.example"
            }
        }

        monitor.watch("first")
        runCurrent() // First extraction is suspended in delay.
        advanceTimeBy(300L)
        monitor.watch("second")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("second:allowed.example"), results)
        monitor.stop()
    }
}
