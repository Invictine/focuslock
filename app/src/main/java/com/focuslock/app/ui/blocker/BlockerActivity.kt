package com.focuslock.app.ui.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.service.AppMonitorAccessibilityService
import com.focuslock.app.service.FrogCoordinator
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class BlockerActivity : ComponentActivity() {

    private var creditReceiver: BroadcastReceiver? = null
    private var homeEnforcementAllowed by mutableStateOf(false)

    /**
     * Frog hard-lock focus session, owned by the activity so it survives every
     * recomposition of [BlockedAppScreen] and keeps ticking while the screen is
     * interactive. [frogSessionStartMs] == 0 means "not running".
     */
    private var frogSessionStartMs = 0L
    private var frogSessionStartElapsedRealtimeMs = 0L
    private val frogSessionElapsedSeconds = mutableLongStateOf(0L)
    private val frogSessionRunning = mutableStateOf(false)
    private var frogSessionTickerJob: Job? = null
    private var frogTimerRecoveryAttempted = false

    /**
     * IO scope for the frog focus work record (the explicit "Stop & log" path).
     * Cancelled in [onDestroy]; an in-flight write is protected by [NonCancellable].
     */
    private val frogWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * STRICT MODE policy (no direct unlock into the blocked app):
     * - verifyTickTickWork(): gated on lockdownModeFlow and refused for
     *   permanent blocks; they finish() only after credits are granted/banked.
     * - ACTION_CREDIT_UPDATED broadcast: consumed while permanent/lockdown without finish().
     * - Back press is intercepted; FLAG_SECURE hides the recents preview; the accessibility
     *   monitor re-fires this screen whenever a blocked app foregrounds again.
     * - Website blocks may leave via continueToChrome(): 90s domain suppression + finish(),
     *   which returns the user to the browser already sitting behind this screen.
     */

    /** Main-thread cache of lockdownModeFlow for the synchronous back-press callback. */
    @Volatile private var lockdownModeCached = false

    /**
     * App name shown on the lock screen. Rendered synchronously from binder-free
     * sources (domain, process-wide label cache, package id) and replaced by the real
     * label once the async PackageManager lookup lands — never a PM call before first
     * frame.
     */
    private val resolvedAppName = mutableStateOf("")

    // Current block target/reason, refreshed from the launch/new intent. Fields (not
    // onCreate locals) so a reused singleTask instance re-renders with the new reason
    // instead of keeping a stale screen with the wrong affordances (F4).
    @Volatile private var blockedWebsite: String? = null
    @Volatile private var blockedPackage: String = "Blocked App"
    @Volatile private var blockReason: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots / recents thumbnail of the lock screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Keep the back-press gate in sync with the DataStore value.
        lifecycleScope.launch {
            FocusLockApplication.instance.settingsRepository.lockdownModeFlow.collect { lockdownModeCached = it }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // FROG HARD LOCK: back never leaves the lock screen — the user must tick
                // today's frog and track the required focus time (or go Home explicitly).
                if (isFrogBlocked()) {
                    Toast.makeText(
                        this@BlockerActivity,
                        "Finish your frog to unlock your phone.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (lockdownModeCached || blockReason == "strict") {
                    Toast.makeText(
                        this@BlockerActivity,
                        "Lockdown mode: unlocking disabled.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    goHome()
                }
            }
        })

        // Reason values are produced by AppMonitorAccessibilityService.triggerBlocker();
        // "permanent" = always-block: no unlock paths are offered on this screen.
        // "frog" = eat-the-frog hard lock: no emergency/credit/verify escapes either.
        readBlockTargetFromIntent()

        // Revalidate the scope while the blocker is visible, including after a
        // permission change or leaving home. Keep the underlying commitment intact.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow(permanent = isPermanentBlockNow())) {
                        homeEnforcementAllowed = false
                        discardFrogFocusSession()
                        if (isFrogBlocked()) com.focuslock.app.service.FrogHomeLauncher.openRegularHome(this@BlockerActivity)
                        finish()
                        return@repeatOnLifecycle
                    }
                    homeEnforcementAllowed = true
                    if (isFrogBlocked() && !frogTimerRecoveryAttempted) {
                        frogTimerRecoveryAttempted = true
                        resumeFrogFocusSession()
                    }
                    delay(10_000L)
                }
            }
        }

        // First frame renders immediately from binder-free state: website domain,
        // process-wide cached label, or the raw package id. The real label resolves
        // on IO and swaps in when it differs.
        resolvedAppName.value = blockedWebsite ?: fastAppNameFromPackage(blockedPackage)
        if (blockedWebsite == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val label = InstalledAppsRepository.getAppLabel(applicationContext, blockedPackage)
                if (label.isNotBlank() && label != blockedPackage) {
                    resolvedAppName.value = label
                }
            }
        }

        // AUTO-UNLOCK RECOVERY (fixes the noHistory race): the credit broadcast receiver
        // below dies with this activity (noHistory=true) whenever the user leaves — e.g.
        // by tapping "Open TickTick" to earn credits. Any credit balance earned SINCE the
        // previous block session therefore unlocks here, when a re-block re-launches this
        // activity. Enforcement is not weakened: permanent blocks never unlock, Lockdown
        // mode is re-checked at unlock time, and only balance GROWTH (vs the stored
        // balance-at-block baseline) unlocks — banked time that already existed when the
        // block started cannot dismiss the screen.
        // FROG HARD LOCK: skipped entirely — credits never lift the frog lock.
        if (!isPermanentBlock() && !isFrogBlocked()) {
            lifecycleScope.launch {
                if (isPermanentBlockNow()) return@launch
                val bank = FocusLockApplication.instance.creditBankRepository
                val balanceNow = try { bank.getBalanceSeconds() } catch (_: Exception) { 0L }
                val prefs = blockSessionPrefs()
                val balanceAtPreviousBlock = prefs.getLong(KEY_BALANCE_AT_BLOCK, Long.MIN_VALUE)
                // Baseline for THIS block session (written after reading the old one).
                prefs.edit()
                    .putLong(KEY_BALANCE_AT_BLOCK, balanceNow)
                    .putLong(KEY_BLOCK_STARTED_AT, System.currentTimeMillis())
                    .apply()
                if (balanceAtPreviousBlock != Long.MIN_VALUE && balanceNow > balanceAtPreviousBlock) {
                    val lockdown = isStrictActive()
                    if (!lockdown) {
                        val earnedMinutes = ((balanceNow - balanceAtPreviousBlock) + 59) / 60
                        Toast.makeText(
                            this@BlockerActivity,
                            "Unlocked! +$earnedMinutes min earned since the last block.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
        }

        // Broadcast listener for automatic unlock when user earns credits in TickTick
        // while this screen is alive. STRICT MODE: the broadcast is consumed WITHOUT
        // finish() — see policy above. (When the user leaves this screen the receiver is
        // gone; the balance check above covers that window.)
        creditReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val earned = intent?.getIntExtra("earnedMinutes", 0) ?: 0
                if (earned <= 0) return
                // FROG HARD LOCK: credits are banked but never dismiss the frog lock.
                // Read dynamically: a singleTask instance may have been re-targeted by
                // onNewIntent since this receiver was registered.
                if (isFrogBlocked()) {
                    Toast.makeText(
                        this@BlockerActivity,
                        "Eat the frog first — credits are saved for later.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                lifecycleScope.launch {
                    // Re-read the dedicated store here: the activity may have been
                    // created before the DataStore collector warmed its mirror.
                    if (isPermanentBlockNow()) {
                        Toast.makeText(
                            this@BlockerActivity,
                            "Permanently blocked: credits saved, this app stays locked.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val lockdown = isStrictActive()
                    if (lockdown) {
                        Toast.makeText(
                            this@BlockerActivity,
                            "Lockdown mode: unlocking disabled. Credits saved for later.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    Toast.makeText(this@BlockerActivity, "Unlocked! Earned +$earned mins in TickTick", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        val filter = IntentFilter(TickTickNotificationListener.ACTION_CREDIT_UPDATED)
        androidx.core.content.ContextCompat.registerReceiver(
            this, creditReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        renderBlockerContent()
    }

    /**
     * Renders the current block target/reason. Called from [onCreate] and again from
     * [onNewIntent] so a reused singleTask instance swaps to the new reason's screen
     * (e.g. limit -> frog) instead of keeping stale affordances.
     */
    private fun renderBlockerContent() {
        val currentWebsite = blockedWebsite
        val currentPackage = blockedPackage
        val currentReason = blockReason
        if (currentReason == FrogCoordinator.REASON_FROG) hideFrogStatusBar()
        else androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        setContent {
            FocusLockTheme {
                if (homeEnforcementAllowed) {
                    if (currentReason == FrogCoordinator.REASON_FROG) {
                        FrogFocusScreen(
                            onOpenFocusLock = { openFocusLock() },
                            onFrogComplete = {
                                discardFrogFocusSession()
                                Toast.makeText(this@BlockerActivity, "Frog complete.", Toast.LENGTH_LONG).show()
                                com.focuslock.app.service.FrogHomeLauncher.openRegularHome(this@BlockerActivity)
                                finish()
                            },
                            onFrogEnded = {
                                discardFrogFocusSession()
                                Toast.makeText(this@BlockerActivity, "Frog lock ended.", Toast.LENGTH_SHORT).show()
                                com.focuslock.app.service.FrogHomeLauncher.openRegularHome(this@BlockerActivity)
                                finish()
                            },
                        )
                    } else {
                        BlockerPresentation(
                            appName = resolvedAppName.value,
                            blockedPackage = currentPackage,
                            isWebsite = currentWebsite != null,
                            blockReason = currentReason,
                            onCloseApp = { goHome() },
                            onOpenFocusLock = { openFocusLock() },
                            onFrogComplete = {
                                Toast.makeText(
                                    this@BlockerActivity,
                                    "Frog complete.",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            },
                            onStaleFrogDismiss = {
                                Toast.makeText(
                                    this@BlockerActivity,
                                    "Frog lock ended.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * singleTask reuse (F4): a new block reason can arrive while this instance lives, so
     * re-read the extras, recompute the reason and re-render — never keep a stale screen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readBlockTargetFromIntent()
        // First-frame name from binder-free sources; the async label lookup follows.
        resolvedAppName.value = blockedWebsite ?: fastAppNameFromPackage(blockedPackage)
        val requestedPackage = blockedPackage
        if (blockedWebsite == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val label = InstalledAppsRepository.getAppLabel(applicationContext, requestedPackage)
                if (label.isNotBlank() && label != requestedPackage && blockedPackage == requestedPackage) {
                    resolvedAppName.value = label
                }
            }
        }
        renderBlockerContent()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFrogBlocked()) hideFrogStatusBar()
    }

    /** Refreshes the block target/reason from the current [intent]. */
    private fun readBlockTargetFromIntent() {
        blockedWebsite = intent.getStringExtra(EXTRA_BLOCKED_WEBSITE)
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "Blocked App"
        blockReason = intent.getStringExtra(EXTRA_BLOCK_REASON)
        // Older callers may omit the reason. Recover the permanent presentation from
        // the dedicated local store so such a launch cannot expose unlock controls.
        val permanentStore = FocusLockApplication.instance.permanentBlocksRepository
        val permanentTarget = blockedWebsite?.let { permanentStore.isPermanentlyBlockedDomain(it) }
            ?: permanentStore.isPermanentlyBlocked(blockedPackage)
        if (blockReason != "permanent" && permanentTarget) {
            blockReason = "permanent"
        }
        if (isFrogBlocked()) com.focuslock.app.service.FrogHomeLauncher.captureFallback(this)
    }

    /** True when the current block reason is the permanent (always-block) reason. */
    private fun isPermanentBlock(): Boolean = blockReason == "permanent"

    /** Re-check the dedicated local store before every unlock-capable action. */
    private suspend fun isPermanentBlockNow(): Boolean {
        if (isPermanentBlock()) return true
        val settings = FocusLockApplication.instance.settingsRepository
        return blockedWebsite?.let { settings.isWebsitePermanent(it) }
            ?: settings.isAppPermanent(blockedPackage)
    }

    /**
     * Synchronous, binder-free name for first render: the process-wide cached label
     * when InstalledAppsRepository has already seen this package, else the legacy
     * pretty map, else the raw package id (the async lookup will replace it).
     */
    private fun fastAppNameFromPackage(packageName: String): String {
        InstalledAppsRepository.getCachedLabelMap()[packageName]?.let { return it }
        return when (packageName) {
            "com.instagram.android" -> "Instagram"
            "com.google.android.youtube" -> "YouTube"
            "com.zhiliaoapp.musically" -> "TikTok"
            "com.reddit.frontpage" -> "Reddit"
            "com.twitter.android" -> "X (Twitter)"
            else -> packageName
        }
    }

    private fun blockSessionPrefs() =
        getSharedPreferences(PREFS_BLOCK_SESSION, MODE_PRIVATE)

    private fun openTickTickApp() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.ticktick.task")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            try {
                val playStoreIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.ticktick.task")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(playStoreIntent)
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ticktick.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            }
        }
    }

    private fun verifyTickTickWork() {
        // FROG HARD LOCK: verification never lifts the frog lock (the screen does not
        // offer it either — this is belt-and-braces).
        if (isFrogBlocked()) return
        lifecycleScope.launch {
            if (isPermanentBlockNow()) {
                Toast.makeText(this@BlockerActivity, "Permanently blocked: verification cannot unlock this app.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val settings = FocusLockApplication.instance.settingsRepository
            // LOCKDOWN MODE gate: work-verify must NOT finish() the blocker. Earned
            // credits stay banked for after Lockdown Mode is turned off.
            if (isStrictActive()) {
                Toast.makeText(
                    this@BlockerActivity,
                    "Lockdown mode: unlocking disabled. Credits saved for later.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val bank = FocusLockApplication.instance.creditBankRepository
            val token = settings.tickTickTokenFlow.first()

            if (token.isNotBlank()) {
                try {
                    val result = FocusLockApplication.instance.tickTickFocusSync.sync()
                    if (result == null) {
                        Toast.makeText(this@BlockerActivity, "TickTick session expired. Reconnect in FocusLock settings.", Toast.LENGTH_LONG).show()
                    } else if (result.sessionsFound == 0) {
                        Toast.makeText(this@BlockerActivity, "No completed focus sessions found today.", Toast.LENGTH_LONG).show()
                    } else if (result.newSessions > 0) {
                        Toast.makeText(
                            this@BlockerActivity,
                            "Verified ${result.newSessions} focus session(s): +${result.earnedMinutes} min earned.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this@BlockerActivity, "Today's focus sessions were already synced.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(this@BlockerActivity, "Focus session sync failed. Check your connection and try again.", Toast.LENGTH_LONG).show()
                }
            }

            val currentBalance = bank.getBalanceSeconds()
            if (currentBalance > 0) {
                Toast.makeText(this@BlockerActivity, "Unlocked with your banked earned time.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@BlockerActivity, "No new work verified yet. Finish a focus session in TickTick or run the Focus Timer first.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // NOTE (finish() audit): every finish() except goHome(), continueToChrome() and the
    // frog-completion auto-dismiss is gated on lockdownModeFlow. The frog dismiss fires
    // only once the frog is COMPLETE (ticked + required focus tracked). Both exceptions
    // only reveal what is already behind this screen: goHome() backgrounds to the
    // launcher, continueToChrome() returns to the browser after a 90s domain suppression.
    // The accessibility monitor re-fires this screen when a blocked app foregrounds
    // again, so neither grants lasting app access.
    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    /**
     * Website escape: let the current domain through for 90 seconds and leave, which
     * returns the user to the browser already behind this screen. Solves the case where
     * a blocked site is Chrome's startup page (otherwise every Chrome launch is blocked).
     */
    private fun continueToChrome(domain: String?) {
        // FROG HARD LOCK: no website escape while the frog is unfinished.
        if (isFrogBlocked() || blockReason == "strict" || lockdownModeCached) return
        if (!domain.isNullOrBlank()) {
            AppMonitorAccessibilityService.suppressDomain(domain, 90_000L)
        }
        finish()
    }

    /** True when the current block reason is the eat-the-frog hard lock. */
    private fun isFrogBlocked(): Boolean = blockReason == FrogCoordinator.REASON_FROG

    private suspend fun isStrictActive(): Boolean = blockReason == "strict" ||
        FocusLockApplication.instance.settingsRepository.isLockdownModeEnabled() ||
        FocusLockApplication.instance.strictModeAutomationRepository.isActivationActiveNow()

    /**
     * Starts the blocker-side frog focus session. Open-ended stopwatch bounded by the
     * 25-minute target the button promises; the elapsed time is live in
     * [frogSessionElapsedSeconds] and banked by [stopFrogFocusSession].
     */
    private fun startFrogFocusSession() {
        if (frogSessionRunning.value) return
        lifecycleScope.launch {
            val app = FocusLockApplication.instance
            val enforceNow = app.homeLocationRepository.shouldEnforceNow(permanent = isPermanentBlockNow())
            val state = app.frogRepository.currentState()
            val task = state.frog
            if (!homeEnforcementAllowed || !enforceNow || !state.locked || !state.toolsConfirmed || task == null) {
                if (!enforceNow) discardFrogFocusSession()
                return@launch
            }
            val nowElapsed = SystemClock.elapsedRealtime()
            frogSessionStartMs = System.currentTimeMillis()
            frogSessionStartElapsedRealtimeMs = nowElapsed
            frogSessionElapsedSeconds.longValue = 0L
            frogSessionRunning.value = true
            blockSessionPrefs().edit()
                .putString(KEY_FROG_TIMER_TASK_ID, task.id)
                .putString(KEY_FROG_TIMER_CYCLE_DATE, state.cycleDate)
                .putLong(KEY_FROG_TIMER_START_ELAPSED, nowElapsed)
                .putLong(KEY_FROG_TIMER_START_WALL, frogSessionStartMs)
                .putInt(KEY_FROG_TIMER_BOOT_COUNT, currentBootCount())
                .apply()
            runFrogFocusTicker()
        }
    }

    /** Restore an unfinished timer only for the exact same locked task and device boot. */
    private fun resumeFrogFocusSession() {
        lifecycleScope.launch {
            val state = FocusLockApplication.instance.frogRepository.currentState()
            val prefs = blockSessionPrefs()
            val startElapsed = prefs.getLong(KEY_FROG_TIMER_START_ELAPSED, 0L)
            val startWall = prefs.getLong(KEY_FROG_TIMER_START_WALL, 0L)
            val bootCount = currentBootCount()
            val enforceNow = FocusLockApplication.instance.homeLocationRepository
                .shouldEnforceNow(permanent = isPermanentBlockNow())
            val matches = state.locked && state.toolsConfirmed && state.frog?.id == prefs.getString(KEY_FROG_TIMER_TASK_ID, null) &&
                state.cycleDate == prefs.getString(KEY_FROG_TIMER_CYCLE_DATE, null) &&
                startElapsed in 1L..SystemClock.elapsedRealtime() && startWall > 0L &&
                bootCount >= 0 && prefs.getInt(KEY_FROG_TIMER_BOOT_COUNT, -1) == bootCount
            if (!matches || !homeEnforcementAllowed || !enforceNow) {
                clearSavedFrogFocusSession()
                return@launch
            }
            frogSessionStartMs = startWall
            frogSessionStartElapsedRealtimeMs = startElapsed
            frogSessionElapsedSeconds.longValue =
                ((SystemClock.elapsedRealtime() - startElapsed) / 1_000L).coerceIn(0L, FROG_SESSION_TARGET_SECONDS)
            frogSessionRunning.value = true
            runFrogFocusTicker()
        }
    }

    private fun runFrogFocusTicker() {
        frogSessionTickerJob?.cancel()
        frogSessionTickerJob = lifecycleScope.launch {
            while (frogSessionRunning.value) {
                val elapsedSeconds = ((SystemClock.elapsedRealtime() - frogSessionStartElapsedRealtimeMs) / 1_000L)
                    .coerceAtLeast(0L)
                if (elapsedSeconds >= FROG_SESSION_TARGET_SECONDS) {
                    finishFrogFocusSession(FROG_SESSION_TARGET_SECONDS)
                    break
                }
                frogSessionElapsedSeconds.longValue = elapsedSeconds
                delay(1_000L)
            }
        }
    }

    private fun finishFrogFocusSession(elapsedSeconds: Long) {
        frogSessionRunning.value = false
        frogSessionTickerJob?.cancel()
        frogSessionTickerJob = null
        frogSessionStartMs = 0L
        frogSessionStartElapsedRealtimeMs = 0L
        frogSessionElapsedSeconds.longValue = 0L
        clearSavedFrogFocusSession()
        logFrogFocusSession(elapsedSeconds)
    }

    private fun discardFrogFocusSession() {
        frogSessionRunning.value = false
        frogSessionTickerJob?.cancel()
        frogSessionTickerJob = null
        frogSessionStartMs = 0L
        frogSessionStartElapsedRealtimeMs = 0L
        frogSessionElapsedSeconds.longValue = 0L
        clearSavedFrogFocusSession()
    }

    private fun clearSavedFrogFocusSession() {
        discardSavedFrogTimer(this)
    }

    private fun currentBootCount(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    } else -1

    /** Stops the session early and banks the whole minutes already focused. */
    private fun stopFrogFocusSession() {
        if (!frogSessionRunning.value) return
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - frogSessionStartElapsedRealtimeMs) / 1_000L)
            .coerceIn(0L, FROG_SESSION_TARGET_SECONDS)
        discardFrogFocusSession()
        if (elapsedSeconds < 60L) {
            Toast.makeText(this, "Focus at least a minute to log it.", Toast.LENGTH_SHORT).show()
            return
        }
        logFrogFocusSession(elapsedSeconds)
    }

    /**
     * Writes the session through the exact path the dashboard Focus Timer uses —
     * one [TickTickWorkRecord] with [WorkRecordSource.MANUAL_ENTRY] and
     * `creditBankRepository.recordWorkCredit(record, ratio, 0)`. That call also
     * advances the frog's tracked seconds (CreditBankRepository hook on focus
     * records), so no direct frog write happens here. Runs on IO; [NonCancellable]
     * keeps it alive while [onDestroy] cancels [frogWriteScope].
     */
    private fun logFrogFocusSession(elapsedSeconds: Long) {
        val minutes = (elapsedSeconds / 60L).toInt()
        if (minutes <= 0) return
        frogWriteScope.launch {
            withContext(NonCancellable) { writeFrogFocusRecord(minutes) }
        }
    }

    /** Suspend body shared by the explicit stop and timer completion paths; never throws. */
    private suspend fun writeFrogFocusRecord(minutes: Int) {
        val app = FocusLockApplication.instance
        try {
            val ratio = app.settingsRepository.workRatioFlow.first()
            val frogTitle = app.frogRepository.currentState().frog?.title
            val record = TickTickWorkRecord(
                id = "frog_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                title = frogTitle ?: "Frog focus session",
                durationMinutes = minutes,
                source = WorkRecordSource.MANUAL_ENTRY,
                projectName = "Eat the Frog"
            )
            // Same call shape as the dashboard Focus Timer; the bank's focus-record
            // hook advances today's frog by these minutes.
            app.creditBankRepository.recordWorkCredit(record, ratio, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "frog work record failed", e)
        }
    }

    /**
     * Opens the main FocusLock app (dashboard/settings) so the user can adjust
     * boundaries instead of fighting the lock screen. CLEAR_TOP returns to the
     * existing task instead of stacking a second MainActivity.
     */
    private fun openFocusLock() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } catch (_: Exception) {
            // If the launcher activity can't start, stay on the lock screen (safe default).
        }
    }

    override fun onDestroy() {
        // A running Frog session is checkpointed in private preferences and resumes
        // only after the same task/cycle is revalidated by the next blocker instance.
        frogWriteScope.cancel()
        super.onDestroy()
        creditReceiver?.let {
            unregisterReceiver(it)
            creditReceiver = null
        }
    }

    companion object {
        /** Clear a running timer when home enforcement is paused by a policy owner. */
        fun discardSavedFrogTimer(context: Context) {
            context.getSharedPreferences(PREFS_BLOCK_SESSION, Context.MODE_PRIVATE).edit()
                .remove(KEY_FROG_TIMER_TASK_ID)
                .remove(KEY_FROG_TIMER_CYCLE_DATE)
                .remove(KEY_FROG_TIMER_START_ELAPSED)
                .remove(KEY_FROG_TIMER_START_WALL)
                .remove(KEY_FROG_TIMER_BOOT_COUNT)
                .apply()
        }

        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
        const val EXTRA_BLOCKED_WEBSITE = "extra_blocked_website"

        /** Must match AppMonitorAccessibilityService.EXTRA_BLOCK_REASON. */
        const val EXTRA_BLOCK_REASON = "extra_block_reason"

        private const val TAG = "BlockerActivity"

        /** Length of the blocker-side frog focus session ("Start 25 min focus"). */
        private const val FROG_SESSION_TARGET_SECONDS = 25L * 60L

        /** Credit-balance baseline (seconds) captured at the start of each block session. */
        private const val PREFS_BLOCK_SESSION = "focuslock_block_session"
        private const val KEY_BALANCE_AT_BLOCK = "balance_at_block_sec"
        private const val KEY_BLOCK_STARTED_AT = "block_started_at"
        private const val KEY_FROG_TIMER_TASK_ID = "frog_timer_task_id"
        private const val KEY_FROG_TIMER_CYCLE_DATE = "frog_timer_cycle_date"
        private const val KEY_FROG_TIMER_START_ELAPSED = "frog_timer_start_elapsed"
        private const val KEY_FROG_TIMER_START_WALL = "frog_timer_start_wall"
        private const val KEY_FROG_TIMER_BOOT_COUNT = "frog_timer_boot_count"
    }
}
