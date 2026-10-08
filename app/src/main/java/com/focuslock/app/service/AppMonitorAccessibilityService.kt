package com.focuslock.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import com.focuslock.app.reminder.RemovalReminderController
import com.focuslock.app.reminder.RemovalReminderStore
import androidx.core.content.ContextCompat
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.data.repository.PermanentBlockPolicy
import com.focuslock.app.data.repository.PermanentWebsitePolicy
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.data.repository.frogCycleDate
import com.focuslock.app.ui.blocker.BlockerActivity
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionReturnWatcher
import java.io.FileDescriptor
import java.io.PrintWriter
import java.time.LocalTime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppMonitorAccessibilityService : AccessibilityService() {

    @Volatile
    private var recentCoroutineErrorClass: String? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            if (error !is kotlinx.coroutines.CancellationException) {
                val errorClass = error.javaClass.simpleName.take(80).ifBlank { "Throwable" }
                recentCoroutineErrorClass = errorClass
                // Intentionally omit exception messages and stack traces: they may contain
                // package data, URLs, or other user state.
                Log.w(TAG, "Uncaught service coroutine error: $errorClass")
            }
        }
    )

    // Written from event callbacks and read from serviceScope (Default) coroutines.
    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var foregroundWindowClass: String? = null

    private var foregroundWindowId: Int? = null
    private val windowClasses = mutableMapOf<Int, Pair<String, String?>>()
    private val popupShield by lazy { PopupBlockShield(this) }
    private var windowTickerJob: Job? = null
    private var windowReconcileJob: Job? = null
    private val popupDomains = mutableMapOf<Int, Pair<String, String>>()

    @Volatile
    private var lastPlayStoreWindowClass: String? = null

    private val billingWindowTracker = FrogBillingWindowTracker()

    @Volatile
    private var redirectCandidate: AppRedirectCandidate? = null
    private val redirectAttemptLimiter = AppRedirectAttemptLimiter()
    private var recoveringRedirect: AppRedirectCandidate? = null
    private var lastExternalAppPackage: String? = null
    private var restoredRedirect: AppRedirectCandidate? = null

    @Volatile
    private var lastAccessibilityEventPackage: String? = null

    @Volatile
    private var lastAccessibilityEventType: Int = 0

    @Volatile
    private var lastAccessibilityEventTimeMs: Long = 0L

    @Volatile
    private var latestAppCheckTarget: String? = null

    @Volatile
    private var latestAppCheckDecision: String = "not_checked"

    // Written from the main-thread event callback and read/cancelled from serviceScope
    // (Default) countdown coroutines — volatile so a stale read can't keep a countdown alive.
    @Volatile
    private var currentActiveWebsite: String? = null

    @Volatile
    private var countdownJob: Job? = null
    @Volatile
    private var policyActivityKey: String? = null
    private var policyActivityRefreshJob: Job? = null
    private var tickTickSessionJob: Job? = null
    private var policyBoundaryJob: Job? = null
    private val browserMonitor by lazy {
        BrowserUrlMonitor(CoroutineScope(serviceScope.coroutineContext + Dispatchers.Main.immediate)) { browser ->
            checkBrowserUrl(browser)
        }
    }
    private val TAG = "AppMonitorAccessibility"

    private val removalReminderStore by lazy { RemovalReminderStore(applicationContext) }
    private var lastRemovalScanMs = -1L
    private var installerWindowClass: String? = null
    private var removalScanJob: Job? = null

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
    private var homeLocationJob: Job? = null
    private var permissionReturnJob: Job? = null
    private val scheduleRefreshMutex = Mutex()

    // "Eat the frog" lock cache: kept current off the event path by a frogStateFlow +
    // wakeHourFlow collector started in onServiceConnected, so the per-package path
    // never suspends on DataStore. Falls back to false before the first emission
    // ("fail open"). The same collector mirrors the fields the event path needs to
    // decide whether to arm the day's frog in-memory (see maybeArmFrogOnForeground).
    @Volatile
    private var frogLocked: Boolean = false

    @Volatile
    private var frogStateCache: com.focuslock.app.data.model.FrogState? = null
    private var frogPromptedCycle: String? = null

    @Volatile
    private var frogEnabled: Boolean = true

    @Volatile
    private var frogArmed: Boolean = false

    @Volatile
    private var frogStoredCycleDate: String = ""

    @Volatile
    private var frogWakeHour: Int = FrogRepository.DEFAULT_WAKE_HOUR

    // Per-cycle guard: at most one arm attempt per cycle from the event path, so
    // repeated foreground changes cannot hammer DataStore.
    @Volatile
    private var frogArmAttemptedCycle: String? = null

    private var frogLockJob: Job? = null
    private var frogWakeReceiver: BroadcastReceiver? = null

    // Target-group membership mirror: a collector started in onServiceConnected keeps
    // TargetGroupsRepository's in-memory index warm, so the per-package group-limit
    // check reads memory only (never DataStore) while an app is opening.
    private var targetGroupsJob: Job? = null
    private var permanentBlocksJob: Job? = null
    private var permanentWebsitesJob: Job? = null

    @Volatile
    private var permanentPackages: Set<String> = emptySet()

    // Default launcher / IME packages for the frog gate's brick mitigation (F7):
    // resolved lazily and cached; null = not resolved (yet).
    @Volatile
    private var defaultLauncherPackage: String? = null

    @Volatile
    private var defaultImePackage: String? = null

    // Cached PowerManager for the doomscroll countdown's screen-state gate (local read,
    // checked per tick — no IPC).
    private val powerManager: PowerManager? by lazy {
        try { getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Exception) { null }
    }

    // AccessibilityNodeInfo.recycle() is deprecated and a no-op from API 33; only older
    // builds need explicit recycling of child nodes acquired over binder.
    private val canRecycleNodes = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

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
        private const val POLICY_ACTIVITY_REFRESH_INTERVAL_MS = 60_000L

        /** TickTick foreground is where the frog gets done — never frog-blocked. */
        private const val TICKTICK_PACKAGE = "com.ticktick.task"
        // Last-resort traversal for browsers whose URL bar matches none of the known view
        // IDs. Depth-capped to keep the binder-call count bounded.
        private const val MAX_URL_SEARCH_DEPTH = 6
        /**
         * Server-side text queries for the cheap fallback URL search — one binder IPC per
         * query instead of a recursive child walk. "http" first (omnibar text for real
         * pages almost always carries the scheme); TLD patterns cover bare-domain bars.
         */
        private val URL_TEXT_QUERIES = listOf("http", ".com", ".org", ".net", ".tv")

        /** Omnibar text is a single URL: reject long/spacey page-prose false positives. */
        private const val MAX_URL_TEXT_LENGTH = 2048

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
            "com.duckduckgo.mobile.android",
            BrowserUrlPolicy.GOOGLE_APP
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
            return BrowserUrlPolicy.urlBarIds(browserPackage, BROWSER_URL_IDS, GENERIC_URL_BAR_RESOURCE_NAMES)
        }
    }

    /** Read-only service diagnostics for adb dumpsys; performs no store or window reads. */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        super.dump(fd, writer, args)
        val out = writer ?: return
        val scopeJob = serviceScope.coroutineContext[Job]
        val homeDiagnostics = try {
            FocusLockApplication.instance.homeLocationRepository.diagnosticSnapshot().asDiagnosticLine()
        } catch (error: Exception) {
            "unavailable=diagnostic_snapshot_error:${error.javaClass.simpleName.take(80)}"
        }
        out.println("FocusLock AppMonitorAccessibilityService diagnostics:")
        out.println("  lastEvent package=${lastAccessibilityEventPackage ?: "unknown"} type=${eventTypeName(lastAccessibilityEventType)}($lastAccessibilityEventType) timeMs=${lastAccessibilityEventTimeMs.takeIf { it > 0L } ?: "unknown"}")
        out.println("  foregroundPackage=${currentForegroundPackage ?: "unknown"}")
        out.println("  foregroundWindowClass=${foregroundWindowClass ?: "unknown"}")
        out.println("  foregroundWindowId=${foregroundWindowId ?: "unknown"} popupShieldRegions=${popupShield.activeRegionCount} popupShieldError=${popupShield.lastError ?: "none"}")
        out.println("  lastPlayStoreWindowClass=${lastPlayStoreWindowClass ?: "unknown"}")
        out.println("  frog locked=$frogLocked phase=${frogStateCache?.phase} toolsConfirmed=${frogStateCache?.toolsConfirmed} allowedTools=${frogStateCache?.allowedToolPackages?.size ?: 0}")
        out.println("  serviceScope active=${scopeJob?.isActive == true} cancelled=${scopeJob?.isCancelled == true}")
        out.println("  jobs homeLocation=${homeLocationJob?.isActive == true} scheduleTicker=${scheduleTickerJob?.isActive == true} permissionReturn=${permissionReturnJob?.isActive == true} frogLock=${frogLockJob?.isActive == true} targetGroups=${targetGroupsJob?.isActive == true} permanentBlocks=${permanentBlocksJob?.isActive == true} permanentWebsites=${permanentWebsitesJob?.isActive == true} countdown=${countdownJob?.isActive == true} policyActivityRefresh=${policyActivityRefreshJob?.isActive == true} policyBoundary=${policyBoundaryJob?.isActive == true} tickTickSession=${tickTickSessionJob?.isActive == true} removalScan=${removalScanJob?.isActive == true}")
        out.println("  latestAppCheck target=${latestAppCheckTarget ?: "unknown"} decision=$latestAppCheckDecision")
        out.println("  recentCoroutineErrorClass=${recentCoroutineErrorClass ?: "none"}")
        out.println("  homeLocation $homeDiagnostics")
        out.flush()
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        0 -> "NONE"
        else -> "OTHER"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowTickerJob?.cancel()
        windowTickerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                requestWindowReconciliation()
                delay(1_000L)
            }
        }
        homeLocationJob?.cancel()
        homeLocationJob = serviceScope.launch {
            var previouslyAllowed: Boolean? = null
            while (isActive) {
                val app = FocusLockApplication.instance
                if (app.homeLocationRepository.homeOnlyFlow.first() && isScreenInteractive()) {
                    val allowed = app.homeLocationRepository.shouldEnforceNow()
                    if (!allowed) {
                        BlockerActivity.discardSavedFrogTimer(applicationContext)
                        countdownJob?.cancel()
                        countdownJob = null
                        stopPolicyActivityRefresh()
                    } else if (previouslyAllowed != true) {
                        // Returning home or losing location must enforce even when Instagram never left
                        // the foreground. Browsers keep their own URL recheck loop.
                        val foreground = currentForegroundPackage
                        if (foreground != null && foreground != applicationContext.packageName) {
                            handleForegroundPackageChanged(foreground, null)
                            if (foreground in BROWSER_PACKAGES) browserMonitor.watch(foreground)
                        }
                    }
                    previouslyAllowed = allowed
                } else if (!app.homeLocationRepository.homeOnlyFlow.first()) {
                    if (previouslyAllowed == false) {
                        currentForegroundPackage?.takeIf { it != applicationContext.packageName }?.let {
                            handleForegroundPackageChanged(it, null)
                        }
                    }
                    previouslyAllowed = true
                }
                delay(10_000L)
            }
        }
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
                // Arm at the wake hour even when the screen stays on without an app switch.
                armAndHandleFrog()
                delay(SCHEDULE_REFRESH_INTERVAL_MS)
            }
        }
        // Bring the app back to the front once a pending permission gets granted while
        // the user is in Android Settings (safe to start activities from a service).
        permissionReturnJob?.cancel()
        permissionReturnJob = PermissionReturnWatcher.watch(this, serviceScope)

        // "Eat the frog": mirror the lock flag AND the fields the event path needs to
        // decide whether to arm in-memory (enabled/armed/cycle date/wake hour), so the
        // per-package path never suspends on DataStore.
        frogLockJob?.cancel()
        frogLockJob = serviceScope.launch {
            try {
                combine(
                    FocusLockApplication.instance.frogRepository.frogStateFlow,
                    FocusLockApplication.instance.frogRepository.wakeHourFlow
                ) { state, wakeHour -> state to wakeHour }
                    .collect { (state, wakeHour) ->
                        val previousState = frogStateCache
                        frogStateCache = state
                        frogLocked = state.locked
                        frogEnabled = state.enabled
                        frogArmed = state.armed
                        frogStoredCycleDate = state.cycleDate
                        frogWakeHour = wakeHour
                        if (state.locked && (previousState?.locked != true ||
                                previousState.allowedToolPackages != state.allowedToolPackages ||
                                previousState.toolsConfirmed != state.toolsConfirmed)) {
                            enforceFrogOnCurrentForeground()
                        }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "frog lock collector failed", e)
            }
        }
        // First service connect after the wake hour: arm the day once. A screen kept on
        // across the wake hour never fires USER_PRESENT/SCREEN_ON, so without this the
        // frog would stay unarmed until an app switch (see maybeArmFrogOnForeground).
        serviceScope.launch { armAndHandleFrog() }

        // Merged target groups: keep the repository's membership index warm off the event
        // path so the group-limit check on app entry never suspends on DataStore. Fail
        // open until the first emission (the repository itself falls back to one read).
        targetGroupsJob?.cancel()
        targetGroupsJob = serviceScope.launch {
            try {
                FocusLockApplication.instance.targetGroupsRepository.groups.collect {
                    reevaluateCurrentPolicyActivity()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "target-groups collector failed", e)
            }
        }
        policyBoundaryJob?.cancel()
        policyBoundaryJob = serviceScope.launch {
            try {
                val settings = FocusLockApplication.instance.settingsRepository
                launch {
                    settings.blockedAppsFlow.distinctUntilChanged().collect {
                        reevaluateCurrentPolicyActivity()
                    }
                }
                launch {
                    settings.blockedWebsitesFlow.distinctUntilChanged().collect {
                        reevaluateCurrentPolicyActivity()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "policy-boundary collector failed", e)
            }
        }
        permanentBlocksJob?.cancel()
        permanentBlocksJob = serviceScope.launch {
            try {
                FocusLockApplication.instance.permanentBlocksRepository.packagesFlow.collect {
                    val previous = permanentPackages
                    permanentPackages = it
                    val foreground = currentForegroundPackage
                    if (foreground != null && foreground in (it - previous) &&
                        !PermanentBlocksRepository.isProtectedPackage(this@AppMonitorAccessibilityService, foreground)) {
                        // A block can be added while the target is already foreground;
                        // no window-state event is guaranteed, so enforce immediately.
                        recordBlock(foreground, "permanent")
                        triggerBlocker(foreground, website = null, reason = "permanent")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "permanent-block collector failed", e)
            }
        }
        permanentWebsitesJob?.cancel()
        permanentWebsitesJob = serviceScope.launch {
            try {
                FocusLockApplication.instance.permanentBlocksRepository.domainsFlow.collect { domains ->
                    val domain = currentActiveWebsite ?: return@collect
                    if (domains.any { PermanentWebsitePolicy.matches(domain, it) }) {
                        val browser = currentForegroundPackage
                        if (browser in BROWSER_PACKAGES) handleDetectedBrowserUrl(browser!!, domain)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "permanent-website collector failed", e)
            }
        }
        // Runtime wake/unlock delivery: the manifest receiver covers boot/package
        // replace only (USER_PRESENT/SCREEN_ON are not reliably manifest-delivered).
        registerFrogWakeReceiver()
        // Rebinding after an update/process restart may happen after the target's
        // window event. Inspect the existing window so a motionless foreground app
        // cannot escape enforcement until the next app switch.
        serviceScope.launch(Dispatchers.Main) {
            repeat(5) {
                if (currentForegroundPackage != null) return@launch
                val root = rootInActiveWindow
                val foreground = try {
                    root?.packageName?.toString()?.also { packageName ->
                        foregroundWindowClass = billingWindowTracker.observe(
                            packageName, root.windowId, root.className?.toString(),
                        )
                    }
                }
                finally { @Suppress("DEPRECATION") root?.recycle() }
                if (foreground != null) {
                    currentForegroundPackage = foreground
                    if (foreground != applicationContext.packageName) {
                        handleForegroundPackageChanged(foreground, null)
                        if (foreground in BROWSER_PACKAGES) browserMonitor.watch(foreground)
                    }
                    return@launch
                }
                delay(250)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        lastAccessibilityEventPackage = event.packageName?.toString()
        lastAccessibilityEventType = event.eventType
        lastAccessibilityEventTimeMs = System.currentTimeMillis()

        // Window layout/focus events can have no package (Samsung pop-up view/DeX).
        // Resolve actual application windows before reading an event package.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshForegroundWindow(readInteractiveAppWindows())
            requestWindowReconciliation()
            return
        }

        // Cheapest possible gate first: only these event types are consumed.
        // Everything else (focus, text selection, scroll notifications from all apps)
        // is dropped before even reading the package name off the parcel.
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val eventPackage = event.packageName?.toString() ?: return
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val observedClass = billingWindowTracker.observe(eventPackage, event.windowId, event.className?.toString())
            if (event.windowId >= 0) windowClasses[event.windowId] = eventPackage to observedClass
            val appWindows = readInteractiveAppWindows()
            val foreground = InteractiveWindowPolicy.foreground(appWindows)
            refreshForegroundWindow(appWindows)
            requestWindowReconciliation()
            // A background popup or Samsung caption event is not a foreground switch.
            if (foreground != null && foreground.packageName != eventPackage) return
        }
        val restored = restoredRedirect
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && restored != null &&
            eventPackage == restored.targetPackage &&
            SystemClock.elapsedRealtime() - restored.observedAtMs in 0..1_000L
        ) {
            val activePackage = activeWindowPackage()
            // Back can finish before queued events from the closed destination arrive.
            // Ignore only a demonstrably stale event; a real redirect is still checked.
            if (activePackage != null && activePackage != eventPackage) return
        }
        val isInstallerEvent = eventPackage in RemovalAttemptPolicy.installerPackages
        val isForegroundBrowserEvent = eventPackage in BROWSER_PACKAGES &&
            eventPackage == currentForegroundPackage

        // Content-change events are extremely noisy across ordinary apps. They are only
        // actionable here for installer reminders and the currently monitored browser;
        // discard all others before the remaining event-path work.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            !isInstallerEvent && !isForegroundBrowserEvent
        ) return

        // Our own package (BlockerActivity/NukeActivity overlay) means the tracked app
        // was left. Without this, currentForegroundPackage never updates and the
        // doomscroll countdown keeps draining credits while the blocker is showing.
        if (eventPackage == applicationContext.packageName) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val previousPackage = currentForegroundPackage
                if (previousPackage != null && previousPackage != eventPackage) {
                    currentForegroundPackage = eventPackage
                    foregroundWindowClass = null
                    redirectCandidate = null
                    currentActiveWebsite = null
                    stopTrackingForPreviousPackage(previousPackage)
                }
            }
            return
        }

        // Installer dialogs often populate after the window event, so inspect bounded,
        // throttled content changes too. This remains active after admin is disabled.
        if (isInstallerEvent) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                installerWindowClass = event.className?.toString()
                lastRemovalScanMs = -1L
            }
            if (checkRemovalReminder(eventPackage)) return
            if (removalReminderStore.enabled && removalScanJob?.isActive != true) {
                removalScanJob = serviceScope.launch(Dispatchers.Main) {
                    delay(550)
                    checkRemovalReminder(eventPackage)
                }
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val previousPackage = currentForegroundPackage
            val eventWindowClass = event.className?.toString()
            val windowClass = billingWindowTracker.observe(eventPackage, event.windowId, eventWindowClass)
            if (eventPackage == "com.android.vending") lastPlayStoreWindowClass = eventWindowClass
            if (eventPackage != previousPackage) {
                currentForegroundPackage = eventPackage
                foregroundWindowClass = windowClass
                currentActiveWebsite = null
                handleForegroundPackageChanged(eventPackage, previousPackage)
            } else if (eventPackage == "com.android.vending" && windowClass != foregroundWindowClass
            ) {
                // Billing and store browsing share a package. Recheck actual activity
                // changes so dismissing a purchase never leaves store browsing exempt.
                // Generic events retain an activity only within that exact window.
                foregroundWindowClass = windowClass
                handleForegroundPackageChanged(eventPackage, previousPackage)
            }
        }

        // Keep checking while the browser is foreground. Chrome may emit its final
        // navigation event before the URL is readable; dropping that event must not
        // leave the page unchecked until another navigation.
        // Window-state processing above may have just made this browser foreground.
        // Re-evaluate here so its first event starts monitoring immediately.
        if (eventPackage in BROWSER_PACKAGES && eventPackage == currentForegroundPackage) {
            browserMonitor.watch(eventPackage)
        }
    }

    /** Read immutable window metadata; every acquired framework object is released here. */
    @Suppress("DEPRECATION")
    private fun readInteractiveAppWindows(): List<InteractiveAppWindow> {
        val appWindows = mutableListOf<InteractiveAppWindow>()
        val windowList = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val displays = windowsOnAllDisplays
                buildList { for (index in 0 until displays.size()) addAll(displays.valueAt(index)) }
            } else windows
        }.getOrDefault(emptyList())
        for (window in windowList) {
            try {
                // Accessibility shields must never occlude themselves. Keep native
                // system/IME geometry so shields leave their higher-layer areas clear.
                val appWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
                if (!appWindow && window.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                    window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = window.root
                try {
                    val pkg = root?.packageName?.toString().orEmpty()
                    if (appWindow && pkg.isBlank()) continue
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    if (bounds.isEmpty) continue
                    appWindows += InteractiveAppWindow(
                        window.id, pkg, window.layer, window.isActive, window.isFocused,
                        WindowBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.displayId else 0,
                        // Keyboard settings are ordinary app windows: track them
                        // as foreground, then exempt them through DeviceAccessPolicy.
                        isAppWindow = appWindow,
                    )
                } finally { if (canRecycleNodes) root?.recycle() }
            } catch (_: Exception) {
                // A closed window can disappear between metadata and root reads.
            } finally { if (canRecycleNodes) runCatching { window.recycle() } }
        }
        return appWindows
    }

    private fun refreshForegroundWindow(appWindows: List<InteractiveAppWindow>) {
        val foreground = InteractiveWindowPolicy.foreground(appWindows) ?: return
        val previous = currentForegroundPackage
        val changedWindow = foregroundWindowId != foreground.id
        foregroundWindowId = foreground.id
        val windowClass = windowClasses[foreground.id]?.takeIf { it.first == foreground.packageName }?.second
        if (previous == foreground.packageName && !changedWindow && windowClass == foregroundWindowClass) return
        currentForegroundPackage = foreground.packageName
        foregroundWindowClass = windowClass
        if (previous != foreground.packageName) currentActiveWebsite = null
        if (foreground.packageName == applicationContext.packageName) {
            redirectCandidate = null
            stopTrackingForPreviousPackage(previous)
        } else {
            handleForegroundPackageChanged(foreground.packageName, previous)
            if (foreground.packageName in BROWSER_PACKAGES) browserMonitor.watch(foreground.packageName)
        }
    }

    /** Owns the returned node. Window identity, rather than event package, chooses the root. */
    @Suppress("DEPRECATION")
    private fun rootForAppWindow(target: InteractiveAppWindow): AccessibilityNodeInfo? {
        val windowList = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowsOnAllDisplays.get(target.displayId).orEmpty()
            else windows
        }.getOrDefault(emptyList())
        var result: AccessibilityNodeInfo? = null
        for (window in windowList) {
            try {
                if (result == null && window.id == target.id && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root
                    if (root?.packageName?.toString() == target.packageName) result = root
                    else if (canRecycleNodes) root?.recycle()
                }
            } finally { if (canRecycleNodes) runCatching { window.recycle() } }
        }
        return result
    }

    private fun rootForForegroundPackage(packageName: String): AccessibilityNodeInfo? {
        val foreground = InteractiveWindowPolicy.foreground(readInteractiveAppWindows())
        if (foreground != null) return if (foreground.packageName == packageName) rootForAppWindow(foreground) else null
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        if (root.packageName?.toString() == packageName) return root
        if (canRecycleNodes) root.recycle()
        return null
    }

    private fun displayBounds(displayId: Int): WindowBounds {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(displayId)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        if (display != null) display.getRealMetrics(metrics) else metrics.setTo(resources.displayMetrics)
        return WindowBounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun requestWindowReconciliation() {
        if (windowReconcileJob?.isActive == true) return
        windowReconcileJob = serviceScope.launch(Dispatchers.Main) {
            try { reconcilePopupWindows() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { Log.w(TAG, "Popup reconciliation failed: ${error.javaClass.simpleName}") }
        }
    }

    /** Browser URL belongs to this window, even when a different app has input focus. */
    private fun popupDomain(window: InteractiveAppWindow): String? {
        val root = rootForAppWindow(window)
            ?: return popupDomains[window.id]?.takeIf { it.first == window.packageName }?.second
        return try {
            val url = extractBrowserUrlFromRoot(root, window.packageName)
            val domain = SettingsRepository.cleanDomain(url).takeIf { it.isNotBlank() }
            if (domain == null) popupDomains.remove(window.id)
            else popupDomains[window.id] = window.packageName to domain
            domain
        } finally { if (canRecycleNodes) root.recycle() }
    }

    private suspend fun popupWebsiteReason(domain: String): String? {
        val app = FocusLockApplication.instance
        val settings = app.settingsRepository
        val permanent = settings.isWebsitePermanent(domain)
        if (permanent) return "permanent"
        if (!app.homeLocationRepository.shouldEnforceNow()) return null
        // Foreground launch enforces the same Frog tool/billing exemption.
        if (isFrogLockActive()) return null
        val blocked = settings.isWebsiteBlocked(domain)
        return WebsiteBlockPolicy.blockReason(
            blocked, false, isGroupLimitExceeded(app, "website", domain),
            blocked && scheduleActiveNow(), isDomainSuppressed(domain), app.creditBankRepository.getBalanceSeconds(),
        )
    }

    private suspend fun reconcilePopupWindows() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (!isScreenInteractive() || keyguard?.isKeyguardLocked == true) {
            popupShield.clear()
            return
        }
        val snapshot = readInteractiveAppWindows()
        refreshForegroundWindow(snapshot)
        val ids = snapshot.map { it.id }.toSet()
        popupDomains.keys.retainAll(ids)
        windowClasses.keys.retainAll(ids)
        val decisions = mutableMapOf<Int, String>()
        for (window in snapshot) {
            if (!window.isAppWindow) continue
            if (window.packageName == applicationContext.packageName || FrogAppPolicy.isHome(this, window.packageName)) continue
            val screen = displayBounds(window.displayId)
            if (!InteractiveWindowPolicy.isMultiWindow(window, snapshot, screen) ||
                InteractiveWindowPolicy.exposedBounds(window, snapshot, screen).isEmpty()) continue
            val windowClass = windowClasses[window.id]?.takeIf { it.first == window.packageName }?.second
            val appDecision = withContext(Dispatchers.Default) { appWindowDecision(window.packageName, windowClass) }
            var reason = blockReasonForDecision(appDecision)
            var domain: String? = null
            if (reason == null && window.packageName in BROWSER_PACKAGES) {
                domain = popupDomain(window)
                if (domain != null) reason = withContext(Dispatchers.Default) { popupWebsiteReason(domain) }
            }
            if (reason != null) decisions[window.id] = reason
        }
        if (!isScreenInteractive() || keyguard?.isKeyguardLocked == true) {
            popupShield.clear()
            return
        }
        // Policy reads suspend. Never shield a closed/moved window using stale geometry.
        val current = readInteractiveAppWindows()
        val targets = current.mapNotNull { window ->
            if (!window.isAppWindow) return@mapNotNull null
            val original = snapshot.firstOrNull { it.id == window.id && it.packageName == window.packageName && it.displayId == window.displayId }
                ?: return@mapNotNull null
            decisions[original.id] ?: return@mapNotNull null
            val screen = displayBounds(window.displayId)
            if (!InteractiveWindowPolicy.isMultiWindow(window, current, screen)) return@mapNotNull null
            val rectangles = InteractiveWindowPolicy.exposedBounds(window, current, screen)
            if (rectangles.isEmpty()) return@mapNotNull null
            PopupShieldTarget(window, rectangles) { openPopupBlocker(window) }
        }
        if (!popupShield.update(targets)) Log.w(TAG, "Popup shield unavailable: ${popupShield.lastError}")
    }

    private fun openPopupBlocker(window: InteractiveAppWindow) {
        serviceScope.launch(Dispatchers.Main) {
            val current = readInteractiveAppWindows().firstOrNull {
                it.id == window.id && it.packageName == window.packageName && it.displayId == window.displayId
            } ?: return@launch
            val windowClass = windowClasses[current.id]?.takeIf { it.first == current.packageName }?.second
            val appReason = withContext(Dispatchers.Default) { blockReasonForDecision(appWindowDecision(current.packageName, windowClass)) }
            val currentDomain = if (appReason == null && current.packageName in BROWSER_PACKAGES) popupDomain(current) else null
            val currentReason = appReason ?: currentDomain?.let { withContext(Dispatchers.Default) { popupWebsiteReason(it) } }
            if (currentReason == null || !isScreenInteractive()) {
                requestWindowReconciliation()
                return@launch
            }
            val activity = if (currentReason == "nuke") com.focuslock.app.ui.nuke.NukeActivity::class.java else BlockerActivity::class.java
            val intent = Intent(this@AppMonitorAccessibilityService, activity).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(BlockerActivity.EXTRA_BLOCKED_PACKAGE, current.packageName)
                putExtra(EXTRA_BLOCK_REASON, currentReason)
                if (currentDomain != null) putExtra(BlockerActivity.EXTRA_BLOCKED_WEBSITE, currentDomain)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(current.displayId).toBundle()
            try { startActivity(intent, options) }
            catch (_: SecurityException) { startActivity(intent) }
        }
    }

    private fun checkRemovalReminder(packageName: String): Boolean {
        if (!removalReminderStore.enabled || RemovalReminderController.isSuppressed(this)) return false
        val now = SystemClock.elapsedRealtime()
        if (lastRemovalScanMs >= 0 && now - lastRemovalScanMs < 500L) return false
        lastRemovalScanMs = now
        val root = rootInActiveWindow ?: return false
        try {
            // Do not inspect a stale window from another app.
            if (root.packageName?.toString() != packageName) return false
            val texts = mutableListOf<String>()
            var visited = 0
            fun collect(node: AccessibilityNodeInfo, depth: Int) {
                if (++visited > 60 || depth > 8) return
                node.text?.toString()?.take(500)?.let(texts::add)
                node.contentDescription?.toString()?.take(500)?.let(texts::add)
                for (index in 0 until minOf(node.childCount, 20)) {
                    if (visited >= 60) break
                    val child = node.getChild(index) ?: continue
                    try { collect(child, depth + 1) } finally {
                        if (canRecycleNodes) child.recycle()
                    }
                }
            }
            collect(root, 0)
            if (RemovalAttemptPolicy.matches(packageName, installerWindowClass, texts)) {
                // A voluntary reminder: Continue returns to the untouched system dialog.
                return RemovalReminderController.show(this, "uninstall", fromForeground = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect uninstall dialog", e)
        } finally {
            if (canRecycleNodes) root.recycle()
        }
        return false
    }

    /**
     * Cancels per-app tracking when the foreground leaves [previousPackage] (real app
     * switch or our blocker/UI taking over) and persists its batched scroll spend.
     */
    private fun stopTrackingForPreviousPackage(previousPackage: String?) {
        browserMonitor.stop()
        stopPolicyActivityRefresh()
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
        // Keyboard/system chrome isn't the app that launched a redirect. Keep the
        // last actual app through those overlays, but clear it on a launcher visit.
        val transientWindow = packageName == "android" || packageName == "com.android.systemui" ||
            packageName == resolveDefaultImePackage() || packageName == applicationContext.packageName
        if (!transientWindow) {
            if (FrogAppPolicy.isHome(this, packageName)) {
                lastExternalAppPackage = null
                redirectCandidate = null
            } else if (previousPackage != packageName) {
                if (previousPackage != null) {
                    redirectCandidate = AppRedirectRecovery.candidate(
                        lastExternalAppPackage ?: previousPackage, packageName,
                        SystemClock.elapsedRealtime(), applicationContext.packageName,
                    )
                }
                lastExternalAppPackage = packageName
            }
        }
        latestAppCheckTarget = packageName
        latestAppCheckDecision = "checking"
        // In-memory-only arm check (F1): a user who keeps the screen on across the wake
        // hour never fires USER_PRESENT/SCREEN_ON, so the first app open after the wake
        // hour must be able to arm the day. This path only reads volatiles; the actual
        // DataStore work is launched off-thread by maybeArmFrogOnForeground.
        maybeArmFrogOnForeground()

        if (previousPackage != packageName) stopTrackingForPreviousPackage(previousPackage)

        serviceScope.launch {
            val app = FocusLockApplication.instance
            if (currentForegroundPackage != packageName) return@launch
            val decision = appWindowDecision(packageName, foregroundWindowClass)
            if (currentForegroundPackage != packageName) return@launch
            latestAppCheckDecision = decision
            if (decision == "update_access_exempt" || decision == "device_access_exempt") {
                stopPolicyActivityRefresh()
                return@launch
            }
            if (decision == "location_paused") {
                BlockerActivity.discardSavedFrogTimer(applicationContext)
                return@launch
            }
            if (decision != "permanent") {
                app.syncManager.requestPolicyRefresh()
                updateAppPolicyActivity(app, packageName)
            }
            when (val reason = blockReasonForDecision(decision)) {
                "nuke" -> {
                    recordBlock(packageName, reason)
                    triggerNuke()
                }
                null -> when (decision) {
                    "ticktick_exempt" -> startTickTickActiveTracking()
                    "countdown" -> if (countdownJob?.isActive != true) startDoomscrollCountdown(packageName, website = null)
                }
                else -> {
                    Log.w(TAG, "Blocking $packageName (reason=$reason)")
                    recordBlock(packageName, reason)
                    triggerBlocker(packageName, website = null, reason = reason)
                }
            }
        }
    }

    /** Shared access decision for foreground enforcement and every visible popup. */
    private suspend fun appWindowDecision(packageName: String, windowClass: String?): String {
        if (DeviceAccessPolicy.isExempt(this, packageName)) return "device_access_exempt"
        if (AppUpdateAccessPolicy.isUpdateApp(packageName)) return "update_access_exempt"
        val app = FocusLockApplication.instance
        val settings = app.settingsRepository
        try { app.permanentBlocksRepository.warm() } catch (_: Exception) { }
        val legacyPermanent = try { settings.isAppPermanent(packageName) } catch (_: Exception) { false }
        val protected = try { PermanentBlocksRepository.isProtectedPackage(this, packageName) }
            catch (_: Exception) { true }
        if (PermanentBlockPolicy.shouldEnforce(
                app.permanentBlocksRepository.isPermanentlyBlocked(packageName) || legacyPermanent, protected,
            )) return "permanent"
        if (!app.homeLocationRepository.shouldEnforceNow()) return "location_paused"
        if (try { settings.isNukeActive() } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { false }) return "nuke"
        if (packageName == TICKTICK_PACKAGE) return "ticktick_exempt"
        if (isFrogLockActive()) {
            if (FrogBillingPolicy.isBillingWindow(packageName, windowClass)) return "frog_billing_allowed"
            val state = frogStateCache
            return if (state != null && FrogAppPolicy.shouldShowFocusScreen(this, packageName, state)) "frog"
                else "frog_allowed_or_state_unavailable"
        }
        if (try { app.appLimitsRepository.isLimitExceeded(packageName) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { false }) return "app_limit"
        if (try { isGroupLimitExceeded(app, "app", packageName) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { false }) return "group_limit"
        if (!settings.isAppBlocked(packageName)) return "not_selected"
        // Strict freezes editing; it never contributes an access verdict.
        return AppBlockPolicy.blockReason(true, scheduleActiveNow(), app.creditBankRepository.getBalanceSeconds())
            ?: "countdown"
    }

    private fun blockReasonForDecision(decision: String): String? = when (decision) {
        "app_limit", "group_limit" -> "limit"
        "permanent", "nuke", "frog", "schedule", "manual" -> decision
        else -> null
    }

    private suspend fun updateAppPolicyActivity(app: FocusLockApplication, packageName: String): Boolean {
        if (DeviceAccessPolicy.isExempt(this, packageName)) {
            stopPolicyActivityRefresh()
            return false
        }
        if (packageName == applicationContext.packageName || packageName in BROWSER_PACKAGES) return false
        if (!app.homeLocationRepository.shouldEnforceNow()) {
            stopPolicyActivityRefresh()
            return false
        }
        val appLimit = try { app.appLimitsRepository.getLimit(packageName) } catch (_: Exception) { null }
        val hasGroupPolicy = try {
            app.targetGroupsRepository.groupsForTarget("app", packageName).any {
                it.limitEnabled && (it.dailyLimitMinutes ?: 0) > 0
            }
        } catch (_: Exception) { false }
        val selectedPolicy = try {
            app.settingsRepository.isAppBlocked(packageName) || app.settingsRepository.isAppPermanent(packageName)
        } catch (_: Exception) { false }
        val hasAppLimit = appLimit?.enabled == true && appLimit.dailyMinutes > 0
        if (selectedPolicy || hasAppLimit || hasGroupPolicy) {
            startPolicyActivityRefresh("app:$packageName", packageName, website = null)
        } else if (policyActivityKey == "app:$packageName") {
            stopPolicyActivityRefresh()
        }
        return selectedPolicy || hasAppLimit || hasGroupPolicy
    }

    private fun reevaluateCurrentPolicyActivity() {
        val packageName = currentForegroundPackage ?: return
        val app = FocusLockApplication.instance
        if (packageName in BROWSER_PACKAGES) {
            val domain = currentActiveWebsite ?: return
            serviceScope.launch {
                val selected = try { app.settingsRepository.isWebsiteBlocked(domain) || app.settingsRepository.isWebsitePermanent(domain) }
                    catch (_: Exception) { false }
                val groupPolicy = try {
                    app.targetGroupsRepository.groupsForWebsiteHost(domain).any {
                        it.limitEnabled && (it.dailyLimitMinutes ?: 0) > 0
                    }
                } catch (_: Exception) { false }
                if (selected || groupPolicy) {
                    startPolicyActivityRefresh("website:$domain", packageName, domain)
                } else if (policyActivityKey == "website:$domain") {
                    stopPolicyActivityRefresh()
                }
            }
        } else {
            serviceScope.launch {
                val alreadyActive = policyActivityKey == "app:$packageName"
                if (updateAppPolicyActivity(app, packageName) && !alreadyActive &&
                    currentForegroundPackage == packageName
                ) {
                    // A boundary or merged cap can arrive from another device while
                    // this app stays foreground. Re-run the ordinary enforcement path
                    // once on the transition into newly active policy.
                    handleForegroundPackageChanged(packageName, previousPackage = null)
                }
            }
        }
    }

    /** Keeps one background-safe refresh loop for the currently used policy target. */
    private fun startPolicyActivityRefresh(key: String, packageName: String, website: String?) {
        if (policyActivityKey == key && policyActivityRefreshJob?.isActive == true) {
            FocusLockApplication.instance.syncManager.requestPolicyRefresh(activeTarget = true)
            return
        }
        stopPolicyActivityRefresh()
        policyActivityKey = key
        val app = FocusLockApplication.instance
        app.syncManager.requestPolicyRefresh(activeTarget = true)
        policyActivityRefreshJob = serviceScope.launch {
            while (isActive && policyActivityKey == key && currentForegroundPackage == packageName &&
                (website == null || currentActiveWebsite == website)
            ) {
                delay(POLICY_ACTIVITY_REFRESH_INTERVAL_MS)
                if (policyActivityKey != key || currentForegroundPackage != packageName ||
                    (website != null && currentActiveWebsite != website)
                ) break
                if (isScreenInteractive()) app.syncManager.requestPolicyRefresh(activeTarget = true)
            }
        }
    }

    private fun stopPolicyActivityRefresh() {
        policyActivityKey = null
        policyActivityRefreshJob?.cancel()
        policyActivityRefreshJob = null
    }

    /**
     * Merged-group daily-limit enforcement for an app package or website host.
     *
     * A target may belong to at most one group (server rule, mirrored by the
     * repository). For every matching group whose limit is
     * enabled, the combined total is
     *
     *     combinedSeconds = max(localSecondsToday, serverSecondsToday)
     *
     * - `localSecondsToday` sums today's UsageStats foreground seconds for the group's
     *   app members; website members contribute 0 locally (browser time reaches the
     *   server from the extension, never from Android UsageStats).
     * - `serverSecondsToday` is the cached per-group total from the last
     *   `usage:getUsageSummary` pull (FocusSyncManager, refreshed at most every minute
     *   while this monitor is using a selected/group target, and every four hours idle).
     *   It may lag one refresh and may not include another device's newest upload.
     *
     * max() avoids counting the phone's own uploaded app usage twice. Android browser
     * time is not measured per-domain locally, so website-only time needs a synced
     * usage total. Cross-device time is picked up when the next pull lands.
     */
    private suspend fun isGroupLimitExceeded(
        app: FocusLockApplication,
        targetKind: String,
        targetKey: String,
    ): Boolean {
        return GroupLimitPolicy.isExceeded(applicationContext, app, targetKind, targetKey)
    }

    /** Records one block event per (package, reason) for the current app entry. */
    private suspend fun recordBlock(packageName: String, reason: String) {
        if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow(permanent = reason == "permanent")) return
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
        if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow()) return
        val packageName = currentForegroundPackage ?: return
        if (DeviceAccessPolicy.isExempt(this, packageName)) return
        if (packageName == applicationContext.packageName) return
        try {
            val settings = FocusLockApplication.instance.settingsRepository
            if (!settings.isAppBlocked(packageName)) return
            // The policy read above suspends. Discard its result if the user has moved
            // to another app, and let the current Frog policy decide any active lock.
            if (currentForegroundPackage != packageName) return
            if (isFrogLockActive()) {
                val state = frogStateCache ?: return
                if (!FrogAppPolicy.shouldShowFocusScreen(this, packageName, state)) {
                    latestAppCheckDecision = "schedule_skipped_frog_allowed"
                    return
                }
                Log.w(TAG, "Frog lock active — blocking $packageName despite active schedule")
                recordBlock(packageName, FrogCoordinator.REASON_FROG)
                triggerBlocker(packageName, website = null, reason = FrogCoordinator.REASON_FROG)
                return
            }
            Log.w(TAG, "Active block schedule — blocking $packageName (already foreground)")
            recordBlock(packageName, "schedule")
            triggerBlocker(packageName, website = null, reason = "schedule")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Schedule enforcement failed for $packageName", e)
        }
    }

    /**
     * Non-suspending read of the cached "eat the frog" lock flag; the flag is kept
     * fresh off the event path (frogStateFlow collector + FROG_ARMED handling).
     */
    private fun isFrogLockActive(): Boolean = frogLocked

    /**
     * Frog-gate-only brick mitigation (F7): exempt the current default launcher and the
     * current default IME in addition to TickTick. Blocking either of these can
     * compound-brick the device (no way home / no keyboard to satisfy the lock), so
     * resolution is lazy + cached and FAILS OPEN: when it fails, the package is not
     * frog-blocked.
     */
    private fun isFrogGateExemptPackage(packageName: String): Boolean {
        if (packageName == TICKTICK_PACKAGE) return true
        val launcher = resolveDefaultLauncherPackage()
        val ime = resolveDefaultImePackage()
        if (launcher == null || ime == null) return true
        return packageName == launcher || packageName == ime
    }

    /** Cached default-launcher package; null while unresolved / on failure (fail open). */
    private fun resolveDefaultLauncherPackage(): String? {
        defaultLauncherPackage?.let { return it }
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = packageManager
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            if (resolved.isNullOrBlank()) {
                null
            } else {
                defaultLauncherPackage = resolved
                resolved
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Cached default-IME package (before the "/"), null on failure (fail open). */
    private fun resolveDefaultImePackage(): String? {
        defaultImePackage?.let { return it }
        return try {
            val component = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val resolved = component?.substringBefore('/')?.takeIf { it.isNotBlank() }
            if (resolved == null) {
                null
            } else {
                defaultImePackage = resolved
                resolved
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cheap in-memory arming trigger for the "first app open after the wake hour" case
     * (F1). The accessibility event path must stay free of disk/DataStore/suspend work:
     * this only reads the volatile mirrors and, at most once per cycle, launches the
     * repository's idempotent [FrogRepository.armIfDue] onto [serviceScope].
     */
    private fun maybeArmFrogOnForeground() {
        if (!frogEnabled || frogArmed) return
        val now = System.currentTimeMillis()
        val wakeHour = frogWakeHour
        if (LocalTime.now().hour < wakeHour) return
        val cycle = frogCycleDate(now, wakeHour)
        // Fail open (F3): a stored cycle date NEWER than the computed one means the
        // clock moved back / wake hour moved forward — never arm or re-lock that day.
        if (frogStoredCycleDate.isNotEmpty() && frogStoredCycleDate > cycle) return
        // At most one arm attempt per cycle from the event path.
        if (frogArmAttemptedCycle == cycle) return
        frogArmAttemptedCycle = cycle
        serviceScope.launch {
            try {
                if (FocusLockApplication.instance.frogRepository.armIfDue(now)) handleFrogArmed()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "frog foreground arm attempt failed", e)
            }
        }
    }

    /** Fresh DataStore read of the frog lock; keeps the cached value on failure. */
    private suspend fun refreshFrogLock() {
        try {
            val state = FocusLockApplication.instance.frogRepository.currentState()
            frogStateCache = state
            frogLocked = state.locked
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /**
     * Registers the runtime frog wake receiver (belt-and-braces next to the manifest
     * [FrogWakeReceiver]): USER_PRESENT/SCREEN_ON arm the day's frog, FROG_ARMED
     * re-evaluates the current foreground immediately. Never crashes the service.
     */
    private fun registerFrogWakeReceiver() {
        if (frogWakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON ->
                        serviceScope.launch { armAndHandleFrog() }
                    FrogCoordinator.ACTION_FROG_ARMED ->
                        serviceScope.launch { handleFrogArmed() }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(FrogCoordinator.ACTION_FROG_ARMED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            frogWakeReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "frog wake receiver registration failed", e)
        }
    }

    /** Unregisters the runtime frog receiver; safe to call more than once. */
    private fun unregisterFrogWakeReceiver() {
        val receiver = frogWakeReceiver ?: return
        frogWakeReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "frog wake receiver unregistration failed", e)
        }
    }

    /** Arms today's frog when due, then runs the shared FROG_ARMED handling. */
    private suspend fun armAndHandleFrog() {
        try {
            FocusLockApplication.instance.frogRepository.armIfDue()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "frog arm attempt failed", e)
        }
        handleFrogArmed()
    }

    /**
     * Shared post-arm handling: refresh the cached lock flag, re-evaluate the current
     * foreground when locked, and warm the open-task cache when it is empty.
     */
    private suspend fun handleFrogArmed() {
        refreshFrogLock()
        if (isFrogLockActive()) {
            showMorningFrogPrompt()
            enforceFrogOnCurrentForeground()
        }
        val openTasksEmpty = try {
            FocusLockApplication.instance.frogRepository.currentState().openTasks.isEmpty()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (openTasksEmpty) {
            FrogCoordinator.refreshOpenTasks(applicationContext, force = true)
        }
    }

    /**
     * Re-evaluates the package already in the foreground when the frog lock turns on
     * (mirrors [enforceScheduleOnCurrentForeground], incl. the screen-state gate).
     */
    private suspend fun enforceFrogOnCurrentForeground() {
        if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow()) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return
        val packageName = currentForegroundPackage ?: return
        if (packageName == applicationContext.packageName) return
        if (!isScreenInteractive()) return
        try {
            val state = frogStateCache ?: return
            if (!FrogAppPolicy.shouldShowFocusScreen(this, packageName, state)) return
            Log.w(TAG, "Frog lock active — blocking $packageName (already foreground)")
            recordBlock(packageName, FrogCoordinator.REASON_FROG)
            triggerBlocker(packageName, website = null, reason = FrogCoordinator.REASON_FROG)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Frog enforcement failed for $packageName", e)
        }
    }

    /** Morning UI waits for unlock; it never covers the keyguard or wakes the display. */
    private suspend fun showMorningFrogPrompt() {
        val state = frogStateCache ?: return
        if (!state.locked || frogPromptedCycle == state.cycleDate || !isScreenInteractive()) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return
        if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow()) return
        val foreground = currentForegroundPackage ?: return
        if (!FrogAppPolicy.shouldShowFocusScreen(this, foreground, state)) return
        triggerBlocker(foreground, website = null, reason = FrogCoordinator.REASON_FROG)
        frogPromptedCycle = state.cycleDate
    }

    private suspend fun checkBrowserUrl(browserPackage: String) {
        if (currentForegroundPackage != browserPackage || !isScreenInteractive()) return
        try {
            // Node IPC stays off Main; the monitor serializes scans and decisions.
            val url = withContext(Dispatchers.Default) { extractBrowserUrl(browserPackage) }
            if (currentForegroundPackage != browserPackage) return
            when {
                url == null -> Unit // Window transition: retry on the next monitor tick.
                url.isBlank() -> clearActiveWebsite()
                else -> handleDetectedBrowserUrl(browserPackage, url)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Browser enforcement check failed", e)
        }
    }

    /** Drops active-website tracking (and its countdown) once it can't be confirmed. */
    private fun clearActiveWebsite() {
        if (currentActiveWebsite != null) {
            currentActiveWebsite = null
            countdownJob?.cancel()
            countdownJob = null
        }
        if (policyActivityKey?.startsWith("website:") == true) stopPolicyActivityRefresh()
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
        val rootNode = rootForForegroundPackage(browserPackage) ?: return null
        try {
            // A queued event may belong to a window that has already closed.
            if (rootNode.packageName?.toString() != browserPackage) return null
            return extractBrowserUrlFromRoot(rootNode, browserPackage)
        } finally {
            if (canRecycleNodes) {
                try { rootNode.recycle() } catch (_: Exception) { }
            }
        }
    }

    private fun extractBrowserUrlFromRoot(rootNode: AccessibilityNodeInfo, browserPackage: String): String {
        // Cheap first-hit pass over the well-known URL-bar view IDs only.
        val quickUrl = try {
            extractUrlFromViewIds(rootNode, browserPackage)
        } catch (_: Exception) {
            null
        }
        if (!quickUrl.isNullOrBlank()) return quickUrl

        // Google search/Discover links are not evidence that a site was opened.
        if (!BrowserUrlPolicy.allowsPageTextFallback(browserPackage)) return ""

        val textUrl = try { findUrlByTextSearch(rootNode) } catch (_: Exception) { null }
        if (!textUrl.isNullOrBlank()) return textUrl

        val deepUrl = try { searchHierarchyForUrl(rootNode, depth = 0) } catch (_: Exception) { null }
        return deepUrl ?: ""
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
                for (node in nodes.orEmpty()) {
                    if (!node.isVisibleToUser) continue
                    val text = BrowserUrlPolicy.toolbarUrl(node.text, node.contentDescription)
                    if (text != null) return text
                }
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
    private suspend fun handleDetectedBrowserUrl(browserPackage: String, url: String) {
        if (currentForegroundPackage != browserPackage) return
        val cleanDomain = SettingsRepository.cleanDomain(url)
        if (cleanDomain.isBlank()) {
            clearActiveWebsite()
            return
        }
        val app = FocusLockApplication.instance
        val settings = app.settingsRepository
        val permanent = settings.isWebsitePermanent(cleanDomain)
        if (permanent) {
            // Permanent websites bypass location, credits, schedules and group policy.
            // Recheck foreground and screen after the suspending store lookup.
            if (currentForegroundPackage != browserPackage || !isScreenInteractive()) return
            currentActiveWebsite = cleanDomain
            countdownJob?.cancel()
            countdownJob = null
            recordBlock(browserPackage, "permanent")
            triggerBlocker(browserPackage, website = cleanDomain, reason = "permanent")
            return
        }
        if (!app.homeLocationRepository.shouldEnforceNow()) {
            clearActiveWebsite()
            return
        }
        app.syncManager.requestPolicyRefresh()
        val websiteGroups = try { app.targetGroupsRepository.groupsForWebsiteHost(cleanDomain) }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
        val groupPolicy = websiteGroups.any { it.limitEnabled && (it.dailyLimitMinutes ?: 0) > 0 }
        val groupLimitExceeded = if (permanent) false else try {
            isGroupLimitExceeded(app, "website", cleanDomain)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        val blocked = settings.isWebsiteBlocked(cleanDomain)
        if (permanent || blocked || groupPolicy) {
            currentActiveWebsite = cleanDomain
            startPolicyActivityRefresh("website:$cleanDomain", browserPackage, cleanDomain)
        } else if (policyActivityKey?.startsWith("website:") == true) {
            stopPolicyActivityRefresh()
        }
        val reason = WebsiteBlockPolicy.blockReason(
            blocked = blocked,
            permanent = permanent,
            groupLimitExceeded = groupLimitExceeded,
            scheduleActive = blocked && scheduleActiveNow(),
            suppressed = isDomainSuppressed(cleanDomain),
            balanceSeconds = app.creditBankRepository.getBalanceSeconds(),
        )
        // Reads above can suspend. Never enforce a result after leaving this browser.
        if (currentForegroundPackage != browserPackage || !isScreenInteractive()) return
        if (reason != null) {
            currentActiveWebsite = cleanDomain
            countdownJob?.cancel()
            countdownJob = null
            recordBlock(browserPackage, reason)
            triggerBlocker(browserPackage, website = cleanDomain, reason = reason)
            return
        }
        if (!blocked || isDomainSuppressed(cleanDomain)) {
            clearActiveWebsite()
            return
        }
        // Re-evaluate rules even on the same site, but do not restart its spending
        // timer on each scan: frequent page events must not postpone consumption.
        if (currentActiveWebsite != cleanDomain || countdownJob?.isActive != true) {
            currentActiveWebsite = cleanDomain
            startDoomscrollCountdown(browserPackage, website = cleanDomain)
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
                if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow()) break
                if (currentForegroundPackage != packageName || (website != null && currentActiveWebsite != website)) break
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
        if (DeviceAccessPolicy.isExempt(this, blockedPackage)) return
        if (AppUpdateAccessPolicy.isUpdateApp(blockedPackage)) return
        if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow(permanent = reason == "permanent")) return
        if (currentForegroundPackage != blockedPackage || !isScreenInteractive()) return
        val candidate = redirectCandidate?.takeIf {
            website == null && reason == FrogCoordinator.REASON_FROG && it.targetPackage == blockedPackage
        }
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
            if (currentForegroundPackage != blockedPackage || !isScreenInteractive()) return@withContext
            if (DeviceAccessPolicy.isExempt(this@AppMonitorAccessibilityService, blockedPackage)) return@withContext
            // A check begun before Frog armed may resume after a settings/store read.
            // Apply the current policy at the actual launch boundary as well.
            val enforceFrog = reason != "permanent" && (reason == FrogCoordinator.REASON_FROG || isFrogLockActive())
            if (enforceFrog) {
                // TickTick's pending purchase resumes inside the Play Store package.
                // Permit only its billing activity; permanent/Nuke rules still win.
                if (FrogBillingPolicy.isBillingWindow(blockedPackage, foregroundWindowClass)) {
                    latestAppCheckDecision = "frog_billing_allowed"
                    return@withContext
                }
                val state = frogStateCache
                if (state == null || !FrogAppPolicy.shouldShowFocusScreen(this@AppMonitorAccessibilityService, blockedPackage, state)) {
                    latestAppCheckDecision = "frog_stale_policy"
                    return@withContext
                }
                val root = rootForForegroundPackage(blockedPackage) ?: run {
                    latestAppCheckDecision = "frog_missing_window"
                    return@withContext
                }
                try {
                    val rootPackage = root.packageName?.toString()
                    if (rootPackage != blockedPackage || rootPackage == applicationContext.packageName) {
                        latestAppCheckDecision = "frog_stale_window"
                        return@withContext
                    }
                } finally {
                    if (canRecycleNodes) runCatching { root?.recycle() }
                }
                intent.putExtra(EXTRA_BLOCK_REASON, FrogCoordinator.REASON_FROG)
                intent.removeExtra(BlockerActivity.EXTRA_BLOCKED_WEBSITE)
                val sourceAllowed = candidate != null && candidate == redirectCandidate &&
                    AppRedirectRecovery.canReturnTo(this@AppMonitorAccessibilityService, candidate.sourcePackage)
                val nukeActive = try {
                    FocusLockApplication.instance.settingsRepository.isNukeActive()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) { true }
                if (currentForegroundPackage != blockedPackage || activeWindowPackage() != blockedPackage) return@withContext
                if (candidate != null && candidate == redirectCandidate &&
                    AppRedirectPolicy.isEligible(
                        candidate, currentForegroundPackage, SystemClock.elapsedRealtime(), sourceAllowed,
                        frogLocked = state.locked, nukeActive = nukeActive, isWebsite = website != null,
                    )
                ) {
                    if (recoveringRedirect?.targetPackage == blockedPackage) return@withContext
                    // A direct package handoff is a recovery candidate, not permission
                    // to use the destination. One Back preserves the source's screen.
                    intent.putExtra(AppRedirectRecovery.EXTRA_RETURN_PACKAGE, candidate.sourcePackage)
                    if (redirectAttemptLimiter.tryAcquire(candidate, SystemClock.elapsedRealtime()) &&
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    ) {
                        latestAppCheckDecision = "redirect_returning"
                        recoveringRedirect = candidate
                        try {
                            repeat(5) {
                                delay(100)
                                if (!isScreenInteractive()) return@withContext
                                val activePackage = activeWindowPackage()
                                if (activePackage == candidate.sourcePackage) {
                                    if (AppRedirectRecovery.canReturnTo(this@AppMonitorAccessibilityService, candidate.sourcePackage)) {
                                        restoredRedirect = candidate.copy(observedAtMs = SystemClock.elapsedRealtime())
                                        currentForegroundPackage = candidate.sourcePackage
                                        foregroundWindowClass = null
                                        currentActiveWebsite = null
                                        lastExternalAppPackage = candidate.sourcePackage
                                        redirectCandidate = null
                                        handleForegroundPackageChanged(candidate.sourcePackage, blockedPackage)
                                        latestAppCheckDecision = "redirect_returned"
                                    }
                                    return@withContext
                                }
                                if (activePackage != null && activePackage != blockedPackage) {
                                    // A redirected activity may own a separate task, so
                                    // Back lands on Home. Resume the permitted source
                                    // once without clearing its in-app navigation.
                                    if ((activePackage == applicationContext.packageName || FrogAppPolicy.isHome(this@AppMonitorAccessibilityService, activePackage)) &&
                                        AppRedirectRecovery.canReturnTo(this@AppMonitorAccessibilityService, candidate.sourcePackage)
                                    ) {
                                        if (AppRedirectRecovery.launchResume(this@AppMonitorAccessibilityService, candidate.sourcePackage)) {
                                            latestAppCheckDecision = "redirect_source_resuming"
                                        }
                                    }
                                    return@withContext
                                }
                            }
                        } finally {
                            recoveringRedirect = null
                        }
                    }
                    // A refused Back or repeated redirect gets an explicit clean-return
                    // action. Never loop through Back/relaunch automatically.
                    if (currentForegroundPackage != blockedPackage || activeWindowPackage() != blockedPackage) return@withContext
                    latestAppCheckDecision = "redirect_return_available"
                }
            }
            startActivity(intent)
        }
    }

    /** Main-thread check of the actual window, rather than a delayed event's package. */
    private fun activeWindowPackage(): String? {
        InteractiveWindowPolicy.foreground(readInteractiveAppWindows())?.let { return it.packageName }
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        return try { root.packageName?.toString() }
        finally { if (canRecycleNodes) root.recycle() }
    }

    private suspend fun triggerNuke() {
        if (currentForegroundPackage?.let { DeviceAccessPolicy.isExempt(this, it) } == true) return
        if (AppUpdateAccessPolicy.isUpdateApp(currentForegroundPackage)) return
        if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow()) return
        if (!isScreenInteractive()) return
        Log.w(TAG, "NUKE active — forcing reset screen")
        try {
            val intent = Intent(this, com.focuslock.app.ui.nuke.NukeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            withContext(Dispatchers.Main) {
                if (currentForegroundPackage?.let { DeviceAccessPolicy.isExempt(this@AppMonitorAccessibilityService, it) } == true) return@withContext
                if (AppUpdateAccessPolicy.isUpdateApp(currentForegroundPackage)) return@withContext
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch NukeActivity", e)
        }
    }

    override fun onInterrupt() {
        windowReconcileJob?.cancel()
        popupShield.clear()
        browserMonitor.stop()
        stopPolicyActivityRefresh()
        policyBoundaryJob?.cancel()
        policyBoundaryJob = null
        countdownJob?.cancel()
        tickTickSessionJob?.cancel()
    }

    override fun onDestroy() {
        windowTickerJob?.cancel()
        windowReconcileJob?.cancel()
        popupShield.clear()
        super.onDestroy()
        homeLocationJob?.cancel()
        homeLocationJob = null
        browserMonitor.stop()
        scheduleTickerJob?.cancel()
        scheduleTickerJob = null
        permissionReturnJob?.cancel()
        permissionReturnJob = null
        frogLockJob?.cancel()
        frogLockJob = null
        targetGroupsJob?.cancel()
        targetGroupsJob = null
        permanentBlocksJob?.cancel()
        permanentBlocksJob = null
        permanentWebsitesJob?.cancel()
        permanentWebsitesJob = null
        stopPolicyActivityRefresh()
        policyBoundaryJob?.cancel()
        policyBoundaryJob = null
        unregisterFrogWakeReceiver()
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
