package com.focuslock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.ui.blocker.BlockerActivity
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionReturnWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppMonitorAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Written from event callbacks and read from serviceScope (Default) coroutines.
    @Volatile
    private var currentForegroundPackage: String? = null

    // Written from the main-thread event callback and read/cancelled from serviceScope
    // (Default) countdown coroutines — volatile so a stale read can't keep a countdown alive.
    @Volatile
    private var currentActiveWebsite: String? = null

    @Volatile
    private var countdownJob: Job? = null
    private var tickTickSessionJob: Job? = null
    private var lastBrowserCheckMs: Long = 0L
    private val TAG = "AppMonitorAccessibility"

    // Device-admin state is cached: the DPM binder IPC must not run per foreground change.
    @Volatile
    private var adminActive: Boolean = false

    @Volatile
    private var adminStateCheckedAtMs: Long = 0L

    // Block-log dedupe: at most one event per (package, reason) per app entry.
    private val blockLogLock = Any()

    @Volatile
    private var lastRecordedBlock: Pair<String, String>? = null

    // Schedule state is cached in memory and refreshed by a 30s ticker / on foreground
    // change (staleness-gated) so accessibility events never hit DataStore directly.
    @Volatile
    private var scheduleActiveCache: Boolean = false

    @Volatile
    private var lastScheduleRefreshMs: Long = 0L
    private var scheduleTickerJob: Job? = null
    private var permissionReturnJob: Job? = null
    private val scheduleRefreshMutex = Mutex()

    // Cached PowerManager for the doomscroll countdown's screen-state gate (local read,
    // checked per tick — no IPC).
    private val powerManager: PowerManager? by lazy {
        try { getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Exception) { null }
    }

    // AccessibilityNodeInfo.recycle() is deprecated and a no-op from API 33; only older
    // builds need explicit recycling of child nodes acquired over binder.
    private val canRecycleNodes = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    // Last time a content-changed event was processed per browser package (event
    // coalescing, see onAccessibilityEvent). Bounded: only BROWSER_PACKAGES write here.
    private val lastContentEventMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // One async DPM refresh at a time; the cached result stays synchronously readable.
    @Volatile
    private var adminRefreshInFlight: Boolean = false

    companion object {
        /** Additive extra passed to BlockerActivity explaining why blocking triggered. */
        const val EXTRA_BLOCK_REASON = "extra_block_reason"

        private val domainSuppressionUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun suppressDomain(domain: String, durationMs: Long) {
            val now = System.currentTimeMillis()
            pruneSuppressedDomains(now)
            domainSuppressionUntil[domain.lowercase().trim()] = now + durationMs
        }

        internal fun isDomainSuppressed(domain: String): Boolean {
            val now = System.currentTimeMillis()
            pruneSuppressedDomains(now)
            return (domainSuppressionUntil[domain.lowercase().trim()] ?: 0L) > now
        }

        /** Suppression entries are tiny — sweep expired ones opportunistically on access. */
        private fun pruneSuppressedDomains(now: Long) {
            if (domainSuppressionUntil.isEmpty()) return
            val iterator = domainSuppressionUntil.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value <= now) iterator.remove()
            }
        }

        private const val SCHEDULE_REFRESH_INTERVAL_MS = 30_000L
        private const val ADMIN_STATE_TTL_MS = 60_000L
        // Last-resort traversal for browsers whose URL bar matches none of the known view
        // IDs. Depth-capped to keep the binder-call count bounded.
        private const val MAX_URL_SEARCH_DEPTH = 6
        private const val URL_CHECK_THROTTLE_MS = 1_500L

        /**
         * TYPE_WINDOW_CONTENT_CHANGED floods in from every foreground app (dozens/sec on
         * busy UIs). Same-package content events inside this window are dropped before any
         * node work — they would only re-run the throttled URL check with nothing new to
         * see. Window-state-changed events are NEVER coalesced: foreground-change detection
         * and enforcement must stay event-exact.
         */
        private const val CONTENT_EVENT_COALESCE_MS = 300L

        /**
         * Server-side text queries for the cheap fallback URL search — one binder IPC per
         * query instead of a recursive child walk. "http" first (omnibar text for real
         * pages almost always carries the scheme); TLD patterns cover bare-domain bars.
         */
        private val URL_TEXT_QUERIES = listOf("http", ".com", ".org", ".net", ".tv")

        /** Omnibar text is a single URL: reject long/spacey page-prose false positives. */
        private const val MAX_URL_TEXT_LENGTH = 2048

        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

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

        /**
         * Fully-qualified URL-bar view IDs per browser (Chromium forks, Firefox, DuckDuckGo).
         * Accessibility view IDs are scoped to the window's own package, so only entries
         * under the active browser's package can ever match — lookups are filtered per
         * package by [urlBarIdsFor] instead of firing every ID every time.
         */
        val BROWSER_URL_IDS = listOf(
            // Chromium family: Chrome / Brave / Edge / Vivaldi / Opera / Samsung Internet
            "com.android.chrome:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.vivaldi.browser:id/url_bar",
            "com.opera.browser:id/url_bar",
            "com.opera.browser:id/omnibar",
            "com.opera.mini.native:id/url_bar",
            "com.opera.touch:id/omnibar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/url_bar",
            // DuckDuckGo: omnibar text field variants
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.duckduckgo.mobile.android:id/omnibarText",
            // Firefox / Fenix: mozac browser-toolbar URL view + legacy Fennec titles
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar",
            "org.mozilla.firefox:id/url_bar_title"
        )

        /**
         * Generic resource names appended (under the browser's own package) after the known
         * IDs, so Chromium/Gecko forks that kept stock resource names still resolve without
         * the expensive hierarchy walk.
         */
        private val GENERIC_URL_BAR_RESOURCE_NAMES = listOf(
            "url_bar",
            "location_bar_edit_text",
            "location_bar",
            "search_box_text",
            "omnibarTextInput",
            "mozac_browser_toolbar_url_view"
        )

        /**
         * URL-bar view IDs worth probing for [browserPackage], cheapest-first: that
         * browser's known IDs, then generic resource names under its own package. Foreign
         * package IDs can never match the window, so skipping them avoids pure-waste
         * binder calls (the old list ran up to 9 sequential searches per event).
         */
        fun urlBarIdsFor(browserPackage: String): List<String> {
            val prefix = "$browserPackage:"
            val specific = BROWSER_URL_IDS.filter { it.startsWith(prefix) }
            val generic = GENERIC_URL_BAR_RESOURCE_NAMES.map { prefix + it }
            return specific + generic
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduleAdminRefresh()
        scheduleTickerJob?.cancel()
        scheduleTickerJob = serviceScope.launch {
            while (isActive) {
                val wasActive = scheduleActiveCache
                val active = refreshScheduleState()
                // A schedule can start while the user is ALREADY inside a blocked app
                // (no re-entry event will fire): enforce on the current foreground.
                if (active && !wasActive) {
                    enforceScheduleOnCurrentForeground()
                }
                delay(SCHEDULE_REFRESH_INTERVAL_MS)
            }
        }
        // Bring the app back to the front once a pending permission gets granted while
        // the user is in Android Settings (safe to start activities from a service).
        permissionReturnJob?.cancel()
        permissionReturnJob = PermissionReturnWatcher.watch(this, serviceScope)
    }

    /**
     * Refreshes the cached device-admin flag with ONE DPM binder call, off the main
     * thread — the event path only ever schedules this, never waits for it.
     */
    private fun scheduleAdminRefresh() {
        if (adminRefreshInFlight) return
        adminRefreshInFlight = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                adminActive = try {
                    PermissionHelper.isDeviceAdminActive(applicationContext)
                } catch (_: Exception) {
                    false
                }
                adminStateCheckedAtMs = System.currentTimeMillis()
            } finally {
                adminRefreshInFlight = false
            }
        }
    }

    /**
     * Cached admin state, re-checked at most once per [ADMIN_STATE_TTL_MS]. The read is
     * synchronous and fast (volatile field); the refresh itself is scheduled off-thread,
     * so a decision may use the previous (≤TTL old) value while the fresh one lands.
     */
    private fun isAdminActiveCached(): Boolean {
        if (System.currentTimeMillis() - adminStateCheckedAtMs >= ADMIN_STATE_TTL_MS) {
            scheduleAdminRefresh()
        }
        return adminActive
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Cheapest possible gate first: only these two event types are ever consumed.
        // Everything else (focus, text selection, scroll notifications from all apps)
        // is dropped before even reading the package name off the parcel.
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val eventPackage = event.packageName?.toString() ?: return

        // Our own package (BlockerActivity/NukeActivity overlay) means the tracked app
        // was left. Without this, currentForegroundPackage never updates and the
        // doomscroll countdown keeps draining credits while the blocker is showing.
        if (eventPackage == applicationContext.packageName) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val previousPackage = currentForegroundPackage
                if (previousPackage != null && previousPackage != eventPackage) {
                    currentForegroundPackage = eventPackage
                    currentActiveWebsite = null
                    stopTrackingForPreviousPackage(previousPackage)
                }
            }
            return
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val previousPackage = currentForegroundPackage
            if (eventPackage != previousPackage) {
                currentForegroundPackage = eventPackage
                currentActiveWebsite = null
                lastContentEventMs.remove(eventPackage)
                handleForegroundPackageChanged(eventPackage, previousPackage)
            }
        }

        // Real-time URL inspection for browsers. Only window state/content changes can
        // alter the URL bar; skipping other event types avoids node work while typing
        // and scrolling.
        if (!BROWSER_PACKAGES.contains(eventPackage)) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Coalesce the content-changed flood: drop same-package events inside the
            // short window BEFORE any node work. The 1.5s URL-check throttle downstream
            // is untouched — this only removes redundant retries of that same check.
            val now = System.currentTimeMillis()
            val last = lastContentEventMs[eventPackage] ?: 0L
            if (now - last < CONTENT_EVENT_COALESCE_MS) return
            lastContentEventMs[eventPackage] = now
        }
        checkBrowserUrl(eventPackage)
    }

    private fun checkUninstallProtection(packageName: String): Boolean {
        // Fast path first: no DPM/binder/node work unless an installer is actually foreground.
        if (packageName !in INSTALLER_PACKAGES) return false
        if (!isAdminActiveCached()) return false

        // Node query only for installer packages (rare); this is the one case where the
        // result gates the handler synchronously, so it stays on the main thread.
        val root = rootInActiveWindow ?: return false
        try {
            val nodes = root.findAccessibilityNodeInfosByText("FocusLock")
            if (!nodes.isNullOrEmpty()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        "FocusLock uninstall is protected. Deactivate admin in FocusLock Settings first.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return true
            }
        } catch (_: Exception) {
        } finally {
            if (canRecycleNodes) {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Cancels per-app tracking when the foreground leaves [previousPackage] (real app
     * switch or our blocker/UI taking over) and persists its batched scroll spend.
     */
    private fun stopTrackingForPreviousPackage(previousPackage: String?) {
        countdownJob?.cancel()
        countdownJob = null
        tickTickSessionJob?.cancel()
        tickTickSessionJob = null
        // New app entry: allow one more block-log event for this (package, reason).
        lastRecordedBlock = null

        if (previousPackage != null) {
            serviceScope.launch {
                try {
                    FocusLockApplication.instance.creditBankRepository.flushPendingScroll()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Failed to flush pending scroll for $previousPackage", e)
                }
            }
        }
    }

    private fun handleForegroundPackageChanged(packageName: String, previousPackage: String?) {
        stopTrackingForPreviousPackage(previousPackage)

        // Intercept uninstallation attempts if uninstall protection is active
        if (checkUninstallProtection(packageName)) {
            return
        }

        serviceScope.launch {
            val app = FocusLockApplication.instance
            val settings = app.settingsRepository
            val bank = app.creditBankRepository

            // 0. NUKE MODE — block everything except the Nuke lock screen itself.
            // Phone + PC stay locked until 10-min reset + coach approval.
            try {
                if (settings.isNukeActive()) {
                    recordBlock(packageName, "nuke")
                    triggerNuke()
                    return@launch
                }
            } catch (_: Exception) { }

            // 1. TickTick active time tracking
            if (packageName == "com.ticktick.task") {
                startTickTickActiveTracking()
                return@launch
            }

            // 2. Per-app daily limit: applies to any app with an enabled limit,
            // whether or not it is part of the blocked set. Runs off the main thread
            // (UsageStats query is IO-safe/suspending).
            val limitExceeded = try {
                app.appLimitsRepository.isLimitExceeded(packageName)
            } catch (_: Exception) {
                false
            }
            if (limitExceeded) {
                Log.w(TAG, "Daily limit reached for $packageName — blocking")
                recordBlock(packageName, "limit")
                triggerBlocker(packageName, website = null, reason = "limit")
                return@launch
            }

            // 2a. Permanent block: cannot be bypassed by balance, schedule, or strict mode.
            if (settings.isAppPermanent(packageName)) {
                Log.w(TAG, "Permanently blocked app launched: $packageName")
                recordBlock(packageName, "permanent")
                triggerBlocker(packageName, website = null, reason = "permanent")
                return@launch
            }

            // 3. Target doomscroll app check
            val isBlocked = settings.isAppBlocked(packageName)
            if (!isBlocked) {
                return@launch
            }

            // 3a. Active block schedule: force blocking regardless of banked time.
            if (scheduleActiveNow()) {
                Log.w(TAG, "Active block schedule — blocking $packageName")
                recordBlock(packageName, "schedule")
                triggerBlocker(packageName, website = null, reason = "schedule")
                return@launch
            }

            val balanceSec = bank.getBalanceSeconds()
            Log.d(TAG, "Blocked app launched: $packageName, remaining balance: $balanceSec s")

            // STRICT MODE: block immediately even with a positive balance — skip the
            // 2s doomscroll grace countdown entirely.
            // NOTE: TickTickNotificationListener has no bypass — it only banks credits
            // and broadcasts ACTION_CREDIT_UPDATED, which BlockerActivity consumes
            // WITHOUT finish() while strict is on.
            val strict = try { settings.isLockdownModeEnabled() } catch (_: Exception) { false }
            if (strict) {
                recordBlock(packageName, "manual")
                triggerBlocker(packageName, website = null, reason = "manual")
            } else if (balanceSec <= 0L) {
                recordBlock(packageName, "manual")
                triggerBlocker(packageName, website = null, reason = "manual")
            } else {
                startDoomscrollCountdown(packageName, website = null)
            }
        }
    }

    /** Records one block event per (package, reason) for the current app entry. */
    private suspend fun recordBlock(packageName: String, reason: String) {
        val key = packageName to reason
        val alreadyRecorded = synchronized(blockLogLock) {
            if (lastRecordedBlock == key) {
                true
            } else {
                lastRecordedBlock = key
                false
            }
        }
        if (alreadyRecorded) return
        try {
            FocusLockApplication.instance.blockLogRepository.record(packageName, reason)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Failed to record block event for $packageName ($reason)", e)
        }
    }

    /** Refreshes the cached schedule flag (DataStore read at most once per refresh). */
    private suspend fun refreshScheduleState(): Boolean = scheduleRefreshMutex.withLock {
        val active = try {
            FocusLockApplication.instance.blockSchedulesRepository.isScheduleActiveNow()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scheduleActiveCache
        }
        scheduleActiveCache = active
        lastScheduleRefreshMs = System.currentTimeMillis()
        active
    }

    /** Cached schedule check, refreshed at most every [SCHEDULE_REFRESH_INTERVAL_MS]. */
    private suspend fun scheduleActiveNow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScheduleRefreshMs < SCHEDULE_REFRESH_INTERVAL_MS) {
            return scheduleActiveCache
        }
        return refreshScheduleState()
    }

    /**
     * Called when the cached schedule transitions inactive -> active. Re-evaluates the
     * current foreground package and triggers the blocker for a blocked app, using the
     * same recordBlock dedupe as normal enforcement (reason "schedule").
     */
    private suspend fun enforceScheduleOnCurrentForeground() {
        val packageName = currentForegroundPackage ?: return
        if (packageName == applicationContext.packageName) return
        try {
            val settings = FocusLockApplication.instance.settingsRepository
            if (!settings.isAppBlocked(packageName)) return
            Log.w(TAG, "Active block schedule — blocking $packageName (already foreground)")
            recordBlock(packageName, "schedule")
            triggerBlocker(packageName, website = null, reason = "schedule")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Schedule enforcement failed for $packageName", e)
        }
    }

    private fun checkBrowserUrl(browserPackage: String) {
        // Throttle: accessibility events fire rapidly; checking more than ~1/sec wastes CPU
        val now = System.currentTimeMillis()
        if (now - lastBrowserCheckMs < URL_CHECK_THROTTLE_MS) return
        lastBrowserCheckMs = now

        // All node access (rootInActiveWindow + findAccessibilityNodeInfosByViewId + the
        // bounded fallback scan) is binder IPC, so it runs off the main thread. Nodes are
        // acquired and released inside this coroutine; only the extracted URL string
        // leaves it, so no AccessibilityNodeInfo is shared across threads.
        serviceScope.launch(Dispatchers.Default) {
            // null = active window unreadable (mid-transition): transient, keep state.
            // "" = window readable but NO url-bar text anywhere: the previous URL can no
            // longer be confirmed, so stop tracking it — a stale currentActiveWebsite
            // must never keep draining credits while the user is on other content.
            val url = try {
                extractBrowserUrl(browserPackage)
            } catch (_: Exception) {
                null
            }
            when {
                url == null -> Unit
                url.isBlank() -> clearActiveWebsite()
                else -> handleDetectedBrowserUrl(browserPackage, url)
            }
        }
    }

    /** Drops active-website tracking (and its countdown) once it can't be confirmed. */
    private fun clearActiveWebsite() {
        if (currentActiveWebsite != null) {
            currentActiveWebsite = null
            countdownJob?.cancel()
            countdownJob = null
        }
    }

    /**
     * Acquires the active-window root and extracts the first URL found, escalating by
     * cost: (1) the browser's known/generic URL-bar view IDs, (2) one server-side text
     * search per pattern — zero per-node binder calls, (3) the depth-capped recursive
     * scan, only if both cheaper passes missed. Returns null when the window itself was
     * unreadable, "" when it was readable but held no URL text. Must run on one thread —
     * every node is owned, used and recycled inside this call.
     */
    @Suppress("DEPRECATION")
    private fun extractBrowserUrl(browserPackage: String): String? {
        val rootNode = rootInActiveWindow ?: return null
        try {
            // Cheap first-hit pass over the well-known URL-bar view IDs only.
            val quickUrl = try {
                extractUrlFromViewIds(rootNode, browserPackage)
            } catch (_: Exception) {
                null
            }
            if (!quickUrl.isNullOrBlank()) return quickUrl

            // Fallback: server-side text search (1 binder call per pattern).
            val textUrl = try {
                findUrlByTextSearch(rootNode)
            } catch (_: Exception) {
                null
            }
            if (!textUrl.isNullOrBlank()) return textUrl

            // Last resort only: bounded depth-first scan.
            val deepUrl = try {
                searchHierarchyForUrl(rootNode, depth = 0)
            } catch (_: Exception) {
                null
            }
            if (!deepUrl.isNullOrBlank()) return deepUrl
            return ""
        } finally {
            if (canRecycleNodes) {
                try { rootNode.recycle() } catch (_: Exception) { }
            }
        }
    }

    /**
     * First non-blank URL-bar text for [browserPackage]'s own view IDs (caller owns
     * [root]). Early-exits on the first hit — each miss is one binder call, so the list
     * is already filtered to IDs that can match this window.
     */
    @Suppress("DEPRECATION")
    private fun extractUrlFromViewIds(root: AccessibilityNodeInfo, browserPackage: String): String? {
        for (id in urlBarIdsFor(browserPackage)) {
            var nodes: List<AccessibilityNodeInfo>? = null
            try {
                nodes = root.findAccessibilityNodeInfosByViewId(id)
                val text = nodes?.firstOrNull()?.text?.toString()
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) {
            } finally {
                nodes?.forEach {
                    if (canRecycleNodes) try { it.recycle() } catch (_: Exception) { }
                }
            }
        }
        return null
    }

    /**
     * Fallback search with no per-node binder calls: the platform matches text
     * server-side and returns candidates. Editable nodes (a real omnibar is an
     * EditText) win immediately; otherwise the first plausible candidate is used so
     * bare-domain URL bars without known IDs still resolve.
     */
    @Suppress("DEPRECATION")
    private fun findUrlByTextSearch(root: AccessibilityNodeInfo): String? {
        for (query in URL_TEXT_QUERIES) {
            var nodes: List<AccessibilityNodeInfo>? = null
            try {
                nodes = root.findAccessibilityNodeInfosByText(query)
                val match = pickUrlTextMatch(nodes)
                if (!match.isNullOrBlank()) return match
            } catch (_: Exception) {
            } finally {
                nodes?.forEach {
                    if (canRecycleNodes) try { it.recycle() } catch (_: Exception) { }
                }
            }
        }
        return null
    }

    /** First URL-like text in [nodes]: any editable node first, else the first plausible one. */
    private fun pickUrlTextMatch(nodes: List<AccessibilityNodeInfo>?): String? {
        if (nodes.isNullOrEmpty()) return null
        var firstPlausible: String? = null
        for (node in nodes) {
            val text = try { node.text?.toString() } catch (_: Exception) { null }?.trim()
            if (!isUrlLikeText(text)) continue
            val editable = try { node.isEditable } catch (_: Exception) { false }
            if (editable) return text
            if (firstPlausible == null) firstPlausible = text
        }
        return firstPlausible
    }

    /**
     * URL heuristics shared by the text-search and the last-resort walk. Omnibar text is
     * a single scheme'd URL or dotted host and never contains spaces — rejecting spacey
     * text stops page prose (any article mentioning "example.com") from hijacking the
     * old contains(".com") check.
     */
    private fun isUrlLikeText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (t.length > MAX_URL_TEXT_LENGTH || t.contains(' ')) return false
        if (t.startsWith("http")) return true
        return t.contains(".com") || t.contains(".org") || t.contains(".net") ||
            t.contains(".tv") || t.contains(".io") || t.contains(".edu") || t.contains(".gov")
    }

    /** Domain check + block decision for a URL found by the fast or fallback scan. */
    private fun handleDetectedBrowserUrl(browserPackage: String, url: String) {
        val cleanDomain = SettingsRepository.cleanDomain(url)
        if (cleanDomain.isBlank()) {
            // Non-URL text (mid-typing, garbage node): same treatment as extraction
            // failure — never keep draining credits off a stale site.
            clearActiveWebsite()
            return
        }

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

            // User-granted temporary pass ("Continue to Chrome"): skip blocking
            // and event recording until the suppression window expires.
            if (isDomainSuppressed(cleanDomain)) return@launch

            if (currentActiveWebsite != cleanDomain) {
                currentActiveWebsite = cleanDomain

                // Permanent block: cannot be bypassed by balance, schedule, or strict mode.
                if (settings.isWebsitePermanent(cleanDomain)) {
                    Log.w(TAG, "Permanently blocked website visited in $browserPackage: $cleanDomain")
                    recordBlock(browserPackage, "permanent")
                    triggerBlocker(browserPackage, website = cleanDomain, reason = "permanent")
                    return@launch
                }

                val balanceSec = bank.getBalanceSeconds()
                Log.d(TAG, "Blocked website visited in $browserPackage: $cleanDomain (balance: $balanceSec s)")

                // STRICT MODE: block immediately even with a positive balance — skip
                // the 2s doomscroll grace countdown entirely (see note above re:
                // TickTickNotificationListener having no bypass).
                val strict = try { settings.isLockdownModeEnabled() } catch (_: Exception) { false }
                if (strict) {
                    recordBlock(browserPackage, "manual")
                    triggerBlocker(browserPackage, website = cleanDomain, reason = "manual")
                } else if (balanceSec <= 0L) {
                    recordBlock(browserPackage, "manual")
                    triggerBlocker(browserPackage, website = cleanDomain, reason = "manual")
                } else {
                    startDoomscrollCountdown(browserPackage, website = cleanDomain)
                }
            }
        }
    }

    /**
     * Last-resort depth-first text scan (only after both cheap passes missed). Depth is
     * capped by [MAX_URL_SEARCH_DEPTH] and exits at the first URL-like text. Pre-33,
     * every child node fetched here is a fresh binder-owned object, so it is recycled by
     * the parent frame; on API 33+ recycle() is a deprecated no-op and is skipped.
     */
    @Suppress("DEPRECATION")
    private fun searchHierarchyForUrl(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > MAX_URL_SEARCH_DEPTH) return null
        val text = try { node.text?.toString() } catch (_: Exception) { null }
        if (isUrlLikeText(text)) return text
        val childCount = node.childCount
        for (i in 0 until childCount) {
            var child: AccessibilityNodeInfo? = null
            try {
                child = node.getChild(i)
                val childResult = searchHierarchyForUrl(child, depth + 1)
                if (childResult != null) return childResult
            } catch (_: Exception) {
            } finally {
                if (canRecycleNodes) {
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
                // Screen-off gate: a blocked app stays foreground while the display is off
                // (user pressed power, pocketed the phone). Consuming credits for time
                // nobody is looking at pauses here and resumes the instant the screen is
                // back. Fail-open on any PowerManager error so a broken read can never
                // under-enforce.
                if (!isScreenInteractive()) continue
                val remaining = bank.consumeScrollTime(intervalSec)
                Log.d(TAG, "Active scroll on ${website ?: packageName}. Remaining: $remaining s")

                if (remaining <= 0L) {
                    recordBlock(packageName, "manual")
                    triggerBlocker(packageName, website, reason = "manual")
                    break
                }
            }
        }
    }

    /** Cheap per-tick screen-state check (cached PowerManager, no IPC). */
    private fun isScreenInteractive(): Boolean = try {
        powerManager?.isInteractive ?: true
    } catch (_: Exception) {
        true
    }

    private fun startTickTickActiveTracking() {
        tickTickSessionJob?.cancel()
        // TickTick foreground is not focus — no auto credit.
        // Focus is credited only from explicit focus completions
        // (notification with parsed duration, Focus Timer, manual log).
        Log.d(TAG, "TickTick foreground is not focus — no auto credit")
        tickTickSessionJob = null
    }

    private suspend fun triggerBlocker(blockedPackage: String, website: String? = null, reason: String? = null) {
        Log.w(TAG, "Lockout triggered for ${website ?: blockedPackage} (reason=${reason ?: "unknown"})")
        val intent = Intent(this, BlockerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(BlockerActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
            if (website != null) {
                putExtra(BlockerActivity.EXTRA_BLOCKED_WEBSITE, website)
            }
            if (reason != null) {
                putExtra(EXTRA_BLOCK_REASON, reason)
            }
        }
        // Background-activity-start restrictions: posting to Main gives startActivity
        // the best chance of succeeding when called from serviceScope (Default).
        withContext(Dispatchers.Main) {
            startActivity(intent)
        }
    }

    private suspend fun triggerNuke() {
        Log.w(TAG, "NUKE active — forcing reset screen")
        try {
            val intent = Intent(this, com.focuslock.app.ui.nuke.NukeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            withContext(Dispatchers.Main) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch NukeActivity", e)
        }
    }

    override fun onInterrupt() {
        countdownJob?.cancel()
        tickTickSessionJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scheduleTickerJob?.cancel()
        scheduleTickerJob = null
        permissionReturnJob?.cancel()
        permissionReturnJob = null
        countdownJob?.cancel()
        tickTickSessionJob?.cancel()
        // Persist any batched scroll seconds before the scope dies. The flush launches on
        // an independent NonCancellable scope so it survives serviceScope.cancel() and
        // never blocks teardown on the main thread; never crash on failure.
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            try {
                FocusLockApplication.instance.creditBankRepository.flushPendingScroll()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to flush pending scroll on service destroy", e)
            }
        }
        serviceScope.cancel()
    }
}
