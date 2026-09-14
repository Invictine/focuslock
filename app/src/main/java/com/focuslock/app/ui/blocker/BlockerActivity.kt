package com.focuslock.app.ui.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.service.AppMonitorAccessibilityService
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

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
                // NORMAL: back exits to Home (same as the on-screen button) — never
                // back into the blocked app.
                // LOCKDOWN: back is swallowed; the user stays on the lock screen.
                if (lockdownModeCached) {
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

        val blockedWebsite = intent.getStringExtra(EXTRA_BLOCKED_WEBSITE)
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "Blocked App"
        // Reason values are produced by AppMonitorAccessibilityService.triggerBlocker();
        // "permanent" = always-block: no unlock paths are offered on this screen.
        val blockReason = intent.getStringExtra(EXTRA_BLOCK_REASON)
        val isPermanentBlock = blockReason == "permanent"

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
        if (!isPermanentBlock) {
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
                // Permanent block: credits are banked but never dismiss this screen.
                if (isPermanentBlock) {
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

        setContent {
            FocusLockTheme {
                PixelBlockerScreen(
                    appName = resolvedAppName.value,
                    blockedPackage = blockedPackage,
                    isWebsite = blockedWebsite != null,
                    onOpenTickTick = { openTickTickApp() },
                    onVerifySync = { verifyTickTickWork() },
                    onGoHome = { goHome() },
                    onEmergencyUnlock = { emergencyUnlock() },
                    onOpenFocusLock = { openFocusLock() },
                    onContinueToChrome = { continueToChrome(blockedWebsite) },
                    blockReason = blockReason
                )
            }
        }
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

    // NOTE (finish() audit): every finish() except goHome() and continueToChrome() is gated
    // on lockdownModeFlow. Both exceptions only reveal what is already behind this screen:
    // goHome() backgrounds to the launcher, continueToChrome() returns to the browser after
    // a 90s domain suppression. The accessibility monitor re-fires this screen when a
    // blocked app foregrounds again, so neither grants lasting app access.
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
        if (!domain.isNullOrBlank()) {
            AppMonitorAccessibilityService.suppressDomain(domain, 90_000L)
        }
        finish()
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
