package com.focuslock.app.ui.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.service.AppMonitorAccessibilityService
import com.focuslock.app.service.FrogCoordinator
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.dashboard.home.FrogPickerBody
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Ten rotating "stop & think" lines. One per view, chosen by today's attempt count % 10. */
private val STOP_THINK_MESSAGES = listOf(
    "Stop. Take one slow breath before anything else.",
    "What were you planning to do before this app caught your attention?",
    "This app will still be here. This moment won't.",
    "Is this attention worth time you'll never get back?",
    "You wrote this boundary on a clearer day. Trust that version of you.",
    "Imagine how you'll feel 20 minutes from now — then choose.",
    "What do you actually need right now: information, boredom relief, or connection?",
    "Discomfort passes. Regret over lost time lingers.",
    "Close this. Do one small thing you'll thank yourself for.",
    "Your future self is watching this moment."
)

class BlockerActivity : ComponentActivity() {

    private val tickTickApiClient = TickTickApiClient()
    private var creditReceiver: BroadcastReceiver? = null

    /**
     * Frog hard-lock focus session, owned by the activity so it survives every
     * recomposition of [FrogBlockerScreen] and keeps ticking while the screen is
     * interactive. [frogSessionStartMs] == 0 means "not running".
     */
    private var frogSessionStartMs = 0L
    private val frogSessionElapsedSeconds = mutableLongStateOf(0L)
    private val frogSessionRunning = mutableStateOf(false)
    private var frogSessionTickerJob: Job? = null

    /**
     * IO scope for the frog focus work record (the explicit "Stop & log" path).
     * Cancelled in [onDestroy]; an in-flight write is protected by [NonCancellable].
     */
    private val frogWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * STRICT MODE policy (no direct unlock into the blocked app):
     * - emergencyUnlock() / verifyTickTickWork(): gated on lockdownModeFlow and refused for
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
                        "Eat the frog to unlock.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (lockdownModeCached) {
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
                    val lockdown = FocusLockApplication.instance.settingsRepository.lockdownModeFlow.first()
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
                // Permanent block: credits are banked but never dismiss this screen.
                if (isPermanentBlock()) {
                    Toast.makeText(
                        this@BlockerActivity,
                        "Permanently blocked: credits saved, this app stays locked.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                lifecycleScope.launch {
                    val lockdown = FocusLockApplication.instance.settingsRepository.lockdownModeFlow.first()
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
        val isFrogBlock = isFrogBlocked()
        val currentWebsite = blockedWebsite
        val currentPackage = blockedPackage
        val currentReason = blockReason
        setContent {
            FocusLockTheme {
                if (isFrogBlock) {
                    FrogBlockerScreen(
                        elapsedSeconds = frogSessionElapsedSeconds.longValue,
                        sessionRunning = frogSessionRunning.value,
                        onStartFocus = { startFrogFocusSession() },
                        onStopFocus = { stopFrogFocusSession() },
                        onGoHome = { goHome() },
                        onOpenFocusLock = { openFocusLock() },
                        onComplete = {
                            Toast.makeText(
                                this@BlockerActivity,
                                "Frog done — boundary apps unlocked.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        },
                        onStaleDismiss = {
                            Toast.makeText(
                                this@BlockerActivity,
                                "Frog lock ended — boundary apps unlocked.",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    )
                } else {
                    PixelBlockerScreen(
                        appName = resolvedAppName.value,
                        blockedPackage = currentPackage,
                        isWebsite = currentWebsite != null,
                        onOpenTickTick = { openTickTickApp() },
                        onVerifySync = { verifyTickTickWork() },
                        onGoHome = { goHome() },
                        onEmergencyUnlock = { emergencyUnlock() },
                        onOpenFocusLock = { openFocusLock() },
                        onContinueToChrome = { continueToChrome(currentWebsite) },
                        blockReason = currentReason
                    )
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

    /** Refreshes the block target/reason from the current [intent]. */
    private fun readBlockTargetFromIntent() {
        blockedWebsite = intent.getStringExtra(EXTRA_BLOCKED_WEBSITE)
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "Blocked App"
        blockReason = intent.getStringExtra(EXTRA_BLOCK_REASON)
    }

    /** True when the current block reason is the permanent (always-block) reason. */
    private fun isPermanentBlock(): Boolean = blockReason == "permanent"

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
            val settings = FocusLockApplication.instance.settingsRepository
            // LOCKDOWN MODE gate: work-verify must NOT finish() the blocker. Earned
            // credits stay banked for after Lockdown Mode is turned off.
            if (settings.lockdownModeFlow.first()) {
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
                val records = tickTickApiClient.fetchCompletedTasksToday(token)
                if (records.isEmpty()) {
                    Toast.makeText(this@BlockerActivity, "No completed TickTick tasks found today.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val ratio = settings.workRatioFlow.first()
                val bonus = settings.taskBonusFlow.first()
                val (newCount, totalEarned) = bank.recordWorkCreditsDeduped(records, ratio, bonus)
                if (newCount > 0) {
                    Toast.makeText(this@BlockerActivity, "Verified! Added +$totalEarned mins.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                Toast.makeText(this@BlockerActivity, "No new tasks since your last unlock.", Toast.LENGTH_LONG).show()
            }

            val currentBalance = bank.getBalanceSeconds()
            if (currentBalance > 0) {
                Toast.makeText(this@BlockerActivity, "Unlocked with your banked earned time.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@BlockerActivity, "No new work verified yet. Complete a task in TickTick or run the Focus Timer first.", Toast.LENGTH_LONG).show()
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
        if (isFrogBlocked()) return
        if (!domain.isNullOrBlank()) {
            AppMonitorAccessibilityService.suppressDomain(domain, 90_000L)
        }
        finish()
    }

    /** True when the current block reason is the eat-the-frog hard lock. */
    private fun isFrogBlocked(): Boolean = blockReason == FrogCoordinator.REASON_FROG

    /**
     * Starts the blocker-side frog focus session. Open-ended stopwatch bounded by the
     * 25-minute target the button promises; the elapsed time is live in
     * [frogSessionElapsedSeconds] and banked by [stopFrogFocusSession].
     */
    private fun startFrogFocusSession() {
        if (frogSessionRunning.value) return
        frogSessionStartMs = System.currentTimeMillis()
        frogSessionElapsedSeconds.longValue = 0L
        frogSessionRunning.value = true
        frogSessionTickerJob?.cancel()
        frogSessionTickerJob = lifecycleScope.launch {
            while (frogSessionRunning.value) {
                val elapsedSeconds = ((System.currentTimeMillis() - frogSessionStartMs) / 1000L)
                    .coerceAtLeast(0L)
                // Stop at the 25-minute target (early stop stays available).
                if (elapsedSeconds >= FROG_SESSION_TARGET_SECONDS) {
                    frogSessionElapsedSeconds.longValue = FROG_SESSION_TARGET_SECONDS
                    frogSessionRunning.value = false
                    frogSessionTickerJob = null
                    frogSessionStartMs = 0L
                    logFrogFocusSession(FROG_SESSION_TARGET_SECONDS)
                    break
                }
                frogSessionElapsedSeconds.longValue = elapsedSeconds
                delay(1_000L)
            }
        }
    }

    /** Stops the session early and banks the whole minutes already focused. */
    private fun stopFrogFocusSession() {
        if (!frogSessionRunning.value) return
        val elapsedSeconds = ((System.currentTimeMillis() - frogSessionStartMs) / 1000L)
            .coerceAtLeast(0L)
        frogSessionRunning.value = false
        frogSessionTickerJob?.cancel()
        frogSessionTickerJob = null
        frogSessionStartMs = 0L
        frogSessionElapsedSeconds.longValue = 0L
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

    /**
     * Best-effort write from [onDestroy] on a transient scope: [frogWriteScope] is
     * cancelled right after this call (as required), which would cancel a coroutine
     * that had not started yet. Mirrors FrogWakeReceiver's throwaway IO scope.
     */
    private fun logFrogFocusSessionDetached(elapsedSeconds: Long) {
        val minutes = (elapsedSeconds / 60L).toInt()
        if (minutes <= 0) return
        CoroutineScope(Dispatchers.IO).launch { writeFrogFocusRecord(minutes) }
    }

    /** Suspend body shared by both frog-session write paths; never throws. */
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

    private fun emergencyUnlock() {
        // FROG HARD LOCK: no emergency pass is available (or rendered) for this reason.
        if (isFrogBlocked()) return
        lifecycleScope.launch {
            val lockdown = FocusLockApplication.instance.settingsRepository.lockdownModeFlow.first()
            if (lockdown) {
                Toast.makeText(this@BlockerActivity, "Lockdown mode: unlocking disabled.", Toast.LENGTH_LONG).show()
                return@launch
            }
            FocusLockApplication.instance.creditBankRepository.addEmergencyCredits(2)
            Toast.makeText(this@BlockerActivity, "2-minute emergency pass granted.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        // Finalize any running frog focus session before the writer scope dies: the
        // pending minutes go out on a detached IO scope (which cancel() cannot cut off),
        // while the explicit "Stop & log" path is protected by NonCancellable.
        if (frogSessionRunning.value) {
            val elapsedSeconds = ((System.currentTimeMillis() - frogSessionStartMs) / 1000L)
                .coerceAtLeast(0L)
            frogSessionRunning.value = false
            frogSessionTickerJob?.cancel()
            frogSessionTickerJob = null
            frogSessionStartMs = 0L
            if (elapsedSeconds >= 60L) logFrogFocusSessionDetached(elapsedSeconds)
        }
        frogWriteScope.cancel()
        super.onDestroy()
        creditReceiver?.let {
            unregisterReceiver(it)
            creditReceiver = null
        }
    }

    companion object {
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
    }
}

/**
 * One clean vertical flow:
 * 1. app name / domain + a single state pill,
 * 2. prominent rotating stop-&-think line,
 * 3. "Attempted N times today" (apps) or "you tried to open <domain>" (websites),
 * 4. minimal actions (Back to Home / Continue to Chrome, TickTick, verify, emergency, FocusLock).
 */
@Composable
fun PixelBlockerScreen(
    appName: String,
    blockedPackage: String,
    isWebsite: Boolean,
    onOpenTickTick: () -> Unit,
    onVerifySync: () -> Unit,
    onGoHome: () -> Unit,
    onEmergencyUnlock: () -> Unit,
    onOpenFocusLock: () -> Unit = {},
    onContinueToChrome: () -> Unit = {},
    blockReason: String? = null
) {
    val isPermanentBlock = blockReason == "permanent"
    // Saveable deadline so rotation doesn't hand out a fresh 10s cooldown. The countdown itself
    // lives in the leaf button below, so a per-second tick never recomposes this screen.
    var emergencyCooldownDeadline by rememberSaveable {
        mutableStateOf(System.currentTimeMillis() + 10_000L)
    }
    val settings = FocusLockApplication.instance.settingsRepository
    val lockdownMode by settings.lockdownModeFlow.collectAsStateWithLifecycle(initialValue = false)

    // Lifecycle-safe attempt counter: block events for this app since local midnight.
    // Nullable while DataStore loads so the stat line can stay hidden.
    val startOfTodayMillis = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val attemptsToday by remember(blockedPackage, startOfTodayMillis) {
        FocusLockApplication.instance.blockLogRepository.eventsFlow.map { events ->
            events.count { it.packageName == blockedPackage && it.timestampMillis >= startOfTodayMillis }
        }
    }.collectAsStateWithLifecycle(initialValue = null)

    // One variation per view: latch the index the first time the count resolves, so a later
    // emission can't swap the message mid-view.
    var thinkIndex by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(attemptsToday) {
        if (thinkIndex < 0 && attemptsToday != null) {
            thinkIndex = attemptsToday!! % STOP_THINK_MESSAGES.size
        }
    }

    val hardState = isPermanentBlock || lockdownMode
    val stateLabel = when {
        isPermanentBlock -> "Permanently blocked"
        lockdownMode -> "Lockdown active"
        else -> "Locked"
    }
    val pillContainerColor = if (hardState) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val pillContentColor = if (hardState) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp)
            ) {
                // Header: app name / website domain + ONE state pill.
                Text(
                    text = appName,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = pillContainerColor,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = pillContentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stateLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = pillContentColor
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Prominent stop & think line.
                Text(
                    text = STOP_THINK_MESSAGES[thinkIndex.coerceAtLeast(0)],
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Stat line: attempts for apps, caption for websites (no count there).
                val attempts = attemptsToday
                when {
                    isWebsite -> Text(
                        text = "you tried to open $appName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    attempts != null && attempts > 0 -> Text(
                        text = "Attempted $attempts times today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Lockdown banner: compact single line (unlocks already gated in the actions).
                if (lockdownMode) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Unlocks, verification and emergency passes are disabled in Lockdown mode.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Permanent notice: apps only (websites already say it in the pill).
                if (isPermanentBlock && !isWebsite) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Always blocked: no emergency pass or work unlock. Turn it off in FocusLock to restore.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                if (isWebsite) {
                    // Website: continue to the browser for 90s unless the site is permanent.
                    if (!isPermanentBlock) {
                        Button(
                            onClick = onContinueToChrome,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Continue to Chrome",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        FilledTonalButton(
                            onClick = onGoHome,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Back to Home", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        // Permanent website: Back to Home + Open FocusLock only.
                        Button(
                            onClick = onGoHome,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Back to Home",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                } else {
                    // App: Back to Home is the primary exit.
                    Button(
                        onClick = onGoHome,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Back to Home",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Secondary: existing TickTick work CTA.
                    FilledTonalButton(
                        onClick = onOpenTickTick,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open TickTick", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Verify stays disabled for permanent blocks (existing gating semantics).
                    TextButton(
                        onClick = onVerifySync,
                        enabled = !lockdownMode && !isPermanentBlock
                    ) {
                        Icon(
                            if (lockdownMode || isPermanentBlock) Icons.Default.Lock else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync & Verify Work", style = MaterialTheme.typography.labelLarge)
                    }
                }

                // Emergency pass: compact text button. Apps only — website blocks already
                // have the explicit "Continue to Chrome" escape, no need for two exits.
                if (!isPermanentBlock && !lockdownMode && !isWebsite) {
                    EmergencyUnlockButton(
                        cooldownDeadlineMillis = emergencyCooldownDeadline,
                        onEmergencyUnlock = onEmergencyUnlock
                    )
                }

                // Compact escape into FocusLock itself (adjust boundaries instead of fighting the lock).
                TextButton(
                    onClick = onOpenFocusLock,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Open FocusLock",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Leaf emergency button: owns the 1s cooldown ticker and derives the remaining seconds from a
 * deadline, so a tick recomposes only this button instead of the whole lock screen.
 */
@Composable
private fun EmergencyUnlockButton(
    cooldownDeadlineMillis: Long,
    onEmergencyUnlock: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldownDeadlineMillis) {
        while (true) {
            delay(1000L)
            nowMs = System.currentTimeMillis()
            if (nowMs >= cooldownDeadlineMillis) break
        }
    }
    val emergencyCooldown by remember(cooldownDeadlineMillis) {
        derivedStateOf {
            val millisLeft = cooldownDeadlineMillis - nowMs
            ((millisLeft + 999L) / 1000L).coerceAtLeast(0L).toInt()
        }
    }
    TextButton(
        onClick = onEmergencyUnlock,
        enabled = emergencyCooldown == 0
    ) {
        Text(
            text = if (emergencyCooldown > 0) {
                "Emergency unlock in ${emergencyCooldown}s..."
            } else {
                "Emergency 2-min Pass"
            },
            color = if (emergencyCooldown == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Fullscreen hard lock for [FrogCoordinator.REASON_FROG]. Same visual language as
 * [PixelBlockerScreen] (charcoal background, muted-rose error surfaces, the same
 * typography/spacing), but the only ways out are:
 *  - tick today's frog off AND track the required focus minutes (auto-finish below),
 *  - "Back to Home" (the accessibility monitor re-blocks on the next boundary open).
 * There is deliberately no emergency pass, credit unlock or TickTick verify path.
 */
@Composable
fun FrogBlockerScreen(
    elapsedSeconds: Long,
    sessionRunning: Boolean,
    onStartFocus: () -> Unit,
    onStopFocus: () -> Unit,
    onGoHome: () -> Unit,
    onComplete: () -> Unit,
    onOpenFocusLock: () -> Unit = {},
    onStaleDismiss: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val frogRepo = FocusLockApplication.instance.frogRepository
    val state by frogRepo.frogStateFlow.collectAsStateWithLifecycle(initialValue = null)

    // Auto-dismiss the lock the moment the frog completes (ticked + enough tracked),
    // or when the gate is no longer active at all (feature off, day rolled over, not
    // armed): a stale frog screen must not stick after the wake-hour rollover (F6).
    LaunchedEffect(state?.phase, state?.locked) {
        when {
            state?.phase == FrogPhase.COMPLETE -> onComplete()
            state?.locked == false -> onStaleDismiss()
        }
    }

    var changingFrog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp)
            ) {
                // Header: same title treatment as the standard blocker + one state pill.
                Text(
                    text = "Eat the frog",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Hard lock",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                val frogState = state
                if (frogState == null) {
                    Text(
                        text = "Loading today's frog…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val requiredSeconds = frogState.requiredSeconds
                    val trackedSeconds = frogState.trackedSeconds
                    val requiredMinutes = requiredSeconds / 60
                    val progress = if (requiredSeconds > 0) {
                        (trackedSeconds / requiredSeconds.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val frog = frogState.frog

                    Text(
                        text = "Every boundary app stays locked until today's frog is " +
                            "ticked off and $requiredMinutes minutes of focus are tracked " +
                            "on it. The lock resets at the next wake hour.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    FrogConditionRow(
                        label = "Frog ticked off",
                        detail = frog?.title ?: "No frog selected yet",
                        checked = frogState.tickedOff
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FrogConditionRow(
                        label = "$requiredMinutes minutes tracked",
                        detail = "${formatBlockerClock(trackedSeconds.toLong())} / " +
                            formatBlockerClock(requiredSeconds.toLong()),
                        checked = requiredSeconds > 0 && trackedSeconds >= requiredSeconds
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    if (frog != null && !changingFrog) {
                        FrogTitleCard(frog = frog)
                        TextButton(onClick = { changingFrog = true }) {
                            Text("Change frog", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Text(
                            text = if (changingFrog && frog != null) {
                                "Change today's frog"
                            } else {
                                "Pick today's frog"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FrogPickerBody(
                            openTasks = frogState.openTasks,
                            onPick = { task ->
                                changingFrog = false
                                scope.launch { frogRepo.selectFrog(task) }
                            },
                            onManual = { title ->
                                if (title.isNotBlank()) {
                                    changingFrog = false
                                    scope.launch {
                                        frogRepo.selectFrog(FrogCoordinator.manualFrog(title))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (changingFrog && frog != null) {
                            TextButton(onClick = { changingFrog = false }) {
                                Text("Keep current frog", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = { scope.launch { frogRepo.tickOffFrog(true) } },
                        enabled = frog != null && !frogState.tickedOff,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (frogState.tickedOff) "Frog ticked off" else "Tick off frog",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (sessionRunning) {
                        Text(
                            text = formatBlockerClock(elapsedSeconds),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum"
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tracking focus on this frog…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = onStopFocus,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Stop & log", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onStartFocus,
                            enabled = frog != null && frogState.phase != FrogPhase.COMPLETE,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start 25 min focus", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Launcher escape only: never back into a boundary app, and the
                // accessibility monitor re-blocks the next boundary-app foreground.
                TextButton(
                    onClick = onGoHome,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Back to Home",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // Self-app escape (F5), same affordance as PixelBlockerScreen: a blocked
                // IME/launcher can never compound-brick the device. Does not weaken
                // boundary blocking — the monitor re-blocks the next boundary open.
                TextButton(
                    onClick = onOpenFocusLock,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Open FocusLock",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** One frog completion condition: filled check once satisfied, outline while pending. */
@Composable
private fun FrogConditionRow(
    label: String,
    detail: String,
    checked: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (checked) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle,
            contentDescription = if (checked) "Requirement met" else "Requirement pending",
            tint = if (checked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The selected frog, in the same tonal-surface idiom as the standard blocker's pill. */
@Composable
private fun FrogTitleCard(frog: FrogTask) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Today's frog",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = frog.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(frog.projectName, frog.dueDate)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** mm:ss clock for the frog lock's live tracked time. */
private fun formatBlockerClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
