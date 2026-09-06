package com.focuslock.app.ui.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BlockerActivity : ComponentActivity() {

    private val tickTickApiClient = TickTickApiClient()
    private var creditReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val blockedWebsite = intent.getStringExtra(EXTRA_BLOCKED_WEBSITE)
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "Blocked App"
        val blockedAppName = blockedWebsite ?: getAppNameFromPackage(blockedPackage)

        // Broadcast listener for automatic unlock when user earns credits in TickTick
        creditReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val earned = intent?.getIntExtra("earnedMinutes", 0) ?: 0
                if (earned > 0) {
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
                    appName = blockedAppName,
                    onOpenTickTick = { openTickTickApp() },
                    onVerifySync = { verifyTickTickWork() },
                    onGoHome = { goHome() },
                    onEmergencyUnlock = { emergencyUnlock() }
                )
            }
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            when (packageName) {
                "com.instagram.android" -> "Instagram"
                "com.google.android.youtube" -> "YouTube"
                "com.zhiliaoapp.musically" -> "TikTok"
                "com.reddit.frontpage" -> "Reddit"
                "com.twitter.android" -> "X (Twitter)"
                else -> packageName
            }
        }
    }

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
            val bank = FocusLockApplication.instance.creditBankRepository
            val token = settings.tickTickTokenFlow.first()

            if (token.isNotBlank()) {
                val records = tickTickApiClient.fetchCompletedTasksToday(token)
                if (records.isNotEmpty()) {
                    val ratio = settings.workRatioFlow.first()
                    val bonus = settings.taskBonusFlow.first()
                    val (newCount, totalEarned) = bank.recordWorkCreditsDeduped(records, ratio, bonus)
                    if (newCount > 0) {
                        Toast.makeText(this@BlockerActivity, "Verified! Added +$totalEarned mins.", Toast.LENGTH_SHORT).show()
                        finish()
                        return@launch
                    } else {
                        Toast.makeText(this@BlockerActivity, "Tasks already credited. Log new work to unlock.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            val currentBalance = bank.getBalanceSeconds()
            if (currentBalance > 0) {
                Toast.makeText(this@BlockerActivity, "Balance verified! Unlocked.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@BlockerActivity, "No new work verified yet. Complete tasks in TickTick or use Focus Timer first!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun emergencyUnlock() {
        lifecycleScope.launch {
            val strict = FocusLockApplication.instance.settingsRepository.strictModeFlow.first()
            if (strict) {
                Toast.makeText(this@BlockerActivity, "Strict Mode is ON — emergency pass disabled. Log work to unlock.", Toast.LENGTH_LONG).show()
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
    }
}

@Composable
fun PixelBlockerScreen(
    appName: String,
    onOpenTickTick: () -> Unit,
    onVerifySync: () -> Unit,
    onGoHome: () -> Unit,
    onEmergencyUnlock: () -> Unit
) {
    var emergencyCooldown by remember { mutableStateOf(10) }
    val settings = FocusLockApplication.instance.settingsRepository
    val bank = FocusLockApplication.instance.creditBankRepository
    val workRatio by settings.workRatioFlow.collectAsState(initial = 4)
    val taskBonus by settings.taskBonusFlow.collectAsState(initial = 5)
    val strictMode by settings.strictModeFlow.collectAsState(initial = false)
    val liveBalance by bank.liveBalanceSeconds.collectAsState()

    // Dynamic example: 30 min of work earns this much at the current ratio
    val exampleWork = 30
    val exampleUnlock = remember(workRatio, taskBonus) {
        com.focuslock.app.data.repository.CreditBankRepository.calculateEarnedMinutes(
            exampleWork, workRatio.coerceAtLeast(1), taskBonus
        )
    }

    LaunchedEffect(Unit) {
        while (emergencyCooldown > 0) {
            delay(1000L)
            emergencyCooldown--
        }
    }

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
                modifier = Modifier.fillMaxWidth()
            ) {
                // Pixel Lock Icon in Glowing Pill Container
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Time to Refocus",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // App Name Status Pill
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                        Text(
                            text = "$appName is locked",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Pixel At-A-Glance Info Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (liveBalance > 0) {
                                "You now have ${liveBalance / 60}m ${liveBalance % 60}s of earned time — leaving re-checks your balance."
                            } else {
                                "You have exhausted your earned leisure time. Log work (TickTick, Focus Timer, or Log Work) to earn screen time."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Target Work",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "$exampleWork min",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Unlocks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "$exampleUnlock min",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            }
                        }
                        Text(
                            text = "At your $workRatio:1 ratio + ${taskBonus}m bonus",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Primary CTA: Pixel Pill Button
                Button(
                    onClick = onOpenTickTick,
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
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open TickTick to Focus",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary CTA: Filled Tonal Pill Button
                FilledTonalButton(
                    onClick = onVerifySync,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Sync & Verify Work",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Home Navigation
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
                        "Back to Home Screen",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Emergency Bypass (hidden in Strict Mode — AppBlock convention)
                if (!strictMode) {
                    TextButton(
                        onClick = onEmergencyUnlock,
                        enabled = emergencyCooldown == 0
                    ) {
                        val label = if (emergencyCooldown > 0) {
                            "Emergency unlock in ${emergencyCooldown}s..."
                        } else {
                            "Emergency 2-min Pass"
                        }
                        Text(
                            text = label,
                            color = if (emergencyCooldown == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    Text(
                        text = "Strict Mode is on — emergency pass disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
