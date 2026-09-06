package com.focuslock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.ui.blocker.BlockerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppMonitorAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentForegroundPackage: String? = null
    private var currentActiveWebsite: String? = null
    private var countdownJob: Job? = null
    private var tickTickSessionJob: Job? = null
    private var lastBrowserCheckMs: Long = 0L
    private val TAG = "AppMonitorAccessibility"

    companion object {
        // Supported Android browsers for website blocking
        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.touch",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android"
        )

        val BROWSER_URL_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox:id/url_bar_title",
            "org.mozilla.firefox:id/toolbar",
            "url_bar",
            "location_bar",
            "search_box_text"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == applicationContext.packageName) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventPackage != currentForegroundPackage) {
                currentForegroundPackage = eventPackage
                currentActiveWebsite = null
                handleForegroundPackageChanged(eventPackage)
            }
        }

        // Real-time URL inspection for browsers
        if (BROWSER_PACKAGES.contains(eventPackage)) {
            checkBrowserUrl(eventPackage)
        }
    }

    private fun handleForegroundPackageChanged(packageName: String) {
        countdownJob?.cancel()
        countdownJob = null
        tickTickSessionJob?.cancel()
        tickTickSessionJob = null

        serviceScope.launch {
            val settings = FocusLockApplication.instance.settingsRepository
            val bank = FocusLockApplication.instance.creditBankRepository

            // 1. TickTick active time tracking
            if (packageName == "com.ticktick.task") {
                startTickTickActiveTracking()
                return@launch
            }

            // 2. Target doomscroll app check
            val isBlocked = settings.isAppBlocked(packageName)
            if (!isBlocked) {
                return@launch
            }

            val balanceSec = bank.getBalanceSeconds()
            Log.d(TAG, "Blocked app launched: $packageName, remaining balance: $balanceSec s")

            if (balanceSec <= 0L) {
                triggerBlocker(packageName)
            } else {
                startDoomscrollCountdown(packageName, website = null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun checkBrowserUrl(browserPackage: String) {
        // Throttle: accessibility events fire rapidly; checking more than ~1/sec wastes CPU
        val now = System.currentTimeMillis()
        if (now - lastBrowserCheckMs < 1500L) return
        lastBrowserCheckMs = now

        val rootNode = rootInActiveWindow ?: return
        val url = try {
            extractUrlFromNode(rootNode)
        } finally {
            try { rootNode.recycle() } catch (_: Exception) { }
        } ?: return
        val cleanDomain = SettingsRepository.cleanDomain(url)

        if (cleanDomain.isBlank()) return

        serviceScope.launch {
            val settings = FocusLockApplication.instance.settingsRepository
            val bank = FocusLockApplication.instance.creditBankRepository

            val isBlocked = settings.isWebsiteBlocked(cleanDomain)
            if (!isBlocked) {
                if (currentActiveWebsite != null) {
                    currentActiveWebsite = null
                    countdownJob?.cancel()
                }
                return@launch
            }

            if (currentActiveWebsite != cleanDomain) {
                currentActiveWebsite = cleanDomain
                val balanceSec = bank.getBalanceSeconds()
                Log.d(TAG, "Blocked website visited in $browserPackage: $cleanDomain (balance: $balanceSec s)")

                if (balanceSec <= 0L) {
                    triggerBlocker(browserPackage, website = cleanDomain)
                } else {
                    startDoomscrollCountdown(browserPackage, website = cleanDomain)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUrlFromNode(root: AccessibilityNodeInfo): String? {
        for (id in BROWSER_URL_IDS) {
            var nodes: List<AccessibilityNodeInfo>? = null
            try {
                nodes = root.findAccessibilityNodeInfosByViewId(id)
                val text = nodes?.firstOrNull()?.text?.toString()
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) {
            } finally {
                nodes?.forEach { try { it.recycle() } catch (_: Exception) { } }
            }
        }

        // Recursive search fallback
        return searchHierarchyForUrl(root, depth = 0)
    }

    @Suppress("DEPRECATION")
    private fun searchHierarchyForUrl(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 8) return null
        val text = try { node.text?.toString() } catch (_: Exception) { null }
        if (!text.isNullOrBlank() && (text.contains(".com") || text.contains(".tv") || text.contains(".org") || text.startsWith("http"))) {
            return text
        }
        for (i in 0 until node.childCount) {
            var child: AccessibilityNodeInfo? = null
            try {
                child = node.getChild(i)
                val childResult = searchHierarchyForUrl(child, depth + 1)
                if (childResult != null) return childResult
            } catch (_: Exception) {
            } finally {
                if (depth > 0) {
                    try { child?.recycle() } catch (_: Exception) { }
                }
            }
        }
        return null
    }

    private fun startDoomscrollCountdown(packageName: String, website: String?) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val bank = FocusLockApplication.instance.creditBankRepository
            val intervalSec = 2L

            while (isActive && currentForegroundPackage == packageName && (website == null || currentActiveWebsite == website)) {
                delay(intervalSec * 1000L)
                val remaining = bank.consumeScrollTime(intervalSec)
                Log.d(TAG, "Active scroll on ${website ?: packageName}. Remaining: $remaining s")

                if (remaining <= 0L) {
                    triggerBlocker(packageName, website)
                    break
                }
            }
        }
    }

    private fun startTickTickActiveTracking() {
        tickTickSessionJob?.cancel()
        tickTickSessionJob = serviceScope.launch {
            val bank = FocusLockApplication.instance.creditBankRepository
            val settings = FocusLockApplication.instance.settingsRepository
            // Credit in 5-minute chunks so work-to-scroll ratio math stays honest
            // (1-min chunks would round up to 1 min leisure each via maxOf(1,...)).
            val intervalSec = 5 * 60L

            while (isActive && currentForegroundPackage == "com.ticktick.task") {
                delay(intervalSec * 1000L)
                if (!isActive || currentForegroundPackage != "com.ticktick.task") break
                val ratio = settings.workRatioFlow.first()

                bank.recordWorkCredit(
                    com.focuslock.app.data.model.TickTickWorkRecord(
                        id = "focus_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}",
                        title = "Active TickTick Focus (5 min)",
                        durationMinutes = 5,
                        source = com.focuslock.app.data.model.WorkRecordSource.TICKTICK_APP_FOCUS,
                        projectName = "In-App Focus"
                    ),
                    ratio,
                    0
                )
            }
        }
    }

    private fun triggerBlocker(blockedPackage: String, website: String? = null) {
        Log.w(TAG, "Lockout triggered for ${website ?: blockedPackage}")
        val intent = Intent(this, BlockerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(BlockerActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
            if (website != null) {
                putExtra(BlockerActivity.EXTRA_BLOCKED_WEBSITE, website)
            }
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        countdownJob?.cancel()
        tickTickSessionJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownJob?.cancel()
        tickTickSessionJob?.cancel()
    }
}
