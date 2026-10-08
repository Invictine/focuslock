package com.focuslock.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.service.AppMonitorAccessibilityService
import com.focuslock.app.service.FrogAppPolicy
import com.focuslock.app.service.FrogHomeLauncher
import com.focuslock.app.ui.blocker.FrogHomeActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in disposable-emulator check. The host runner backs up and restores app preferences. */
@RunWith(AndroidJUnit4::class)
class FrogReturnLatencyTest {
    private var shellAutomation: UiAutomation? = null

    @Test
    fun homeAndBlockedAppReturnsStayFastWithAnAllowedToolOpen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("frogReturnFixture") == "true")
        assumeTrue(Build.MODEL.contains("sdk", ignoreCase = true) || Build.FINGERPRINT.contains("generic"))

        // Instrumentation normally disables third-party accessibility services. Keep
        // the production monitor bound so this exercises real window events and launches.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        shellAutomation = automation
        val app = FocusLockApplication.instance
        val accessibilityManager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val service = ComponentName(app, AppMonitorAccessibilityService::class.java)
        val serviceId = service.flattenToString()
        val oldServices = shell("settings get secure enabled_accessibility_services").trim()
            .takeUnless { it.isBlank() || it == "null" }
        val oldAccessibilityEnabled = shell("settings get secure accessibility_enabled").trim()
            .takeUnless { it.isBlank() || it == "null" }
        val originalHome = shell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        ).lineSequence().map(String::trim).lastOrNull { it.contains('/') && !it.startsWith("priority=") }
            ?: throw AssertionError("Could not resolve the ordinary Home activity")
        val originalHomePackage = ComponentName.unflattenFromString(originalHome)?.packageName
            ?: throw AssertionError("Could not parse the ordinary Home component: $originalHome")
        assertNotEquals("The ordinary launcher must be the starting Home app", app.packageName, originalHomePackage)

        var homeWasChanged = false
        try {
            FrogHomeLauncher.captureFallback(app)
            assertEquals(originalHomePackage, FrogHomeLauncher.fallbackPackage(app))

            val installedHomeServices = oldServices.orEmpty().split(':').filter(String::isNotBlank)
            val otherAccessibilityServices = installedHomeServices.filterNot { it == serviceId }
            // Force a real rebind even when the previous instrumentation run left this
            // service enabled in Settings but its process died with that runner.
            if (otherAccessibilityServices.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${quote(otherAccessibilityServices.joinToString(":"))}")
            shell("settings put secure accessibility_enabled 1")
            await("production accessibility service to disable", 5_000) {
                accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .none { it.resolveInfo.serviceInfo.let { info -> info.packageName == app.packageName && info.name == AppMonitorAccessibilityService::class.java.name } }
            }
            shell("settings put secure enabled_accessibility_services ${quote((otherAccessibilityServices + serviceId).joinToString(":"))}")
            await("production accessibility service to bind", 10_000) {
                shell("dumpsys activity service ${quote("${app.packageName}/${AppMonitorAccessibilityService::class.java.name}")}")
                    .contains("serviceScope active=true")
            }

            val blockedPackage = chooseBlockedLaunchablePackage(app)
            assertTrue(
                "Settings app is required as the confirmed allowed tool",
                app.packageManager.getLaunchIntentForPackage(SETTINGS_PACKAGE) != null,
            )
            assertNotEquals("The target app cannot be the allowed tool", SETTINGS_PACKAGE, blockedPackage)

            app.homeLocationRepository.setHomeOnly(false)
            app.frogRepository.setEnabled(true)
            app.frogRepository.setWakeHour(0)
            app.frogRepository.setRequiredMinutes(30)
            app.frogRepository.armIfDue()
            app.frogRepository.clearFrog()
            app.frogRepository.selectFrog(
                FrogTask("frog-return-latency-fixture", "Return latency fixture", source = FrogTask.SOURCE_MANUAL),
            )
            assertTrue("Settings must be confirmed as a Frog tool", app.frogRepository.confirmTools(setOf(SETTINGS_PACKAGE)))
            val lockedState = app.frogRepository.currentState()
            assertTrue("Fixture requires an active locked Frog", lockedState.locked)
            assertTrue(lockedState.toolsConfirmed && SETTINGS_PACKAGE in lockedState.allowedToolPackages)

            // The primary measurement keeps the user's ordinary launcher as Home:
            // Accessibility must observe Home there and launch BlockerActivity over it.
            assertEquals("Primary phase must preserve the ordinary Home app", originalHomePackage, resolvedHomePackage())

            // Let the real service ingest the seeded policy before measuring launches.
            SystemClock.sleep(1_000)
            // Clear any redirect candidate left by an instrumentation-runner activity
            // and let the first Home enforcement settle before collecting timings.
            shell("am start -W -a android.settings.SETTINGS")
            await("warm-up Settings", 5_000) { isResumed(SETTINGS_PACKAGE) }
            assertStable("warm-up Settings", SETTINGS_PACKAGE, 500)
            shell("input keyevent KEYCODE_HOME")
            await("warm-up Home to be covered by BlockerActivity", 10_000) { isResumed(BLOCKER_TOKEN) }
            assertStable("warm-up BlockerActivity over ordinary Home", BLOCKER_TOKEN, 500)
            val homeSamples = mutableListOf<Long>()
            val blockedSamples = mutableListOf<Long>()
            val gestureHome = shell("settings get secure navigation_mode").trim() == "2"

            repeat(3) { iteration ->
                val blockedElapsed = measureUntilResumed("blocked app launch $iteration", BLOCKER_TOKEN) {
                    shell("monkey -p ${quote(blockedPackage)} 1")
                }
                blockedSamples += blockedElapsed
                assertFast("blocked app return", blockedElapsed)
                assertStable("BlockerActivity", BLOCKER_TOKEN, 500)

                val allowedElapsed = measureUntilResumed("allowed Settings launch $iteration", SETTINGS_PACKAGE) {
                    shell("monkey -p ${quote(SETTINGS_PACKAGE)} 1")
                }
                assertTrue("Allowed Settings should open promptly (${allowedElapsed}ms)", allowedElapsed <= MAX_RETURN_MS)
                assertStable("allowed Settings", SETTINGS_PACKAGE, 500)

                val homeAction = if (gestureHome && iteration == 1) "swipe" else "key"
                val homeElapsed = measureUntilResumed("ordinary Home $homeAction return $iteration", BLOCKER_TOKEN) {
                    sendHome(homeAction)
                }
                homeSamples += homeElapsed
                assertFast("ordinary Home return", homeElapsed)
                assertEquals("The ordinary launcher must stay the configured Home app", originalHomePackage, resolvedHomePackage())
                assertStable("BlockerActivity over ordinary Home", BLOCKER_TOKEN, 500)

                val reblockedElapsed = measureUntilResumed("blocked app re-entry $iteration", BLOCKER_TOKEN) {
                    shell("monkey -p ${quote(blockedPackage)} 1")
                }
                blockedSamples += reblockedElapsed
                assertFast("blocked app re-entry", reblockedElapsed)
                assertStable("BlockerActivity after re-entry", BLOCKER_TOKEN, 500)
            }

            reportTimings(instrumentation, "ordinaryHome", homeSamples, blockedSamples)

            // Optional second phase covers the direct FrogHomeActivity route separately.
            // It is opt-in because it temporarily changes the system Home role.
            if (InstrumentationRegistry.getArguments().getString("frogHomeReturnFixture") == "true") {
                homeWasChanged = true
                shell("cmd package set-home-activity --user 0 ${quote("${app.packageName}/${FrogHomeActivity::class.java.name}")}")
                await("Frog Home to become the default Home activity", 5_000) { FrogHomeLauncher.isDefault(app) }
                val directHomeSamples = mutableListOf<Long>()
                val directBlockedSamples = mutableListOf<Long>()
                repeat(2) { iteration ->
                    val settingsElapsed = measureUntilResumed("optional Settings launch $iteration", SETTINGS_PACKAGE) {
                        shell("monkey -p ${quote(SETTINGS_PACKAGE)} 1")
                    }
                    assertFast("optional allowed Settings launch", settingsElapsed)
                    assertStable("allowed Settings", SETTINGS_PACKAGE, 500)

                    val homeAction = if (gestureHome && iteration == 1) "swipe" else "key"
                    val homeElapsed = measureUntilResumed("direct Frog Home $homeAction return $iteration", FROG_HOME_TOKEN) {
                        sendHome(homeAction)
                    }
                    directHomeSamples += homeElapsed
                    assertFast("direct Frog Home return", homeElapsed)
                    assertStable("FrogHomeActivity", FROG_HOME_TOKEN, 500)

                    val blockedElapsed = measureUntilResumed("optional blocked app re-entry $iteration", BLOCKER_TOKEN) {
                        shell("monkey -p ${quote(blockedPackage)} 1")
                    }
                    directBlockedSamples += blockedElapsed
                    assertFast("optional blocked app re-entry", blockedElapsed)
                    assertStable("BlockerActivity after direct Home", BLOCKER_TOKEN, 500)
                }
                reportTimings(instrumentation, "frogHome", directHomeSamples, directBlockedSamples)
            }
        } catch (error: Throwable) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("returnFailureService", shell("dumpsys activity service com.focuslock.app/.service.AppMonitorAccessibilityService"))
                putString("returnFailureActivity", activityDump().lineSequence().filter { it.contains("ResumedActivity", ignoreCase = true) }.joinToString("\n"))
            })
            throw error
        } finally {
            // Restore device-level state even when an assertion or launch fails.
            if (homeWasChanged) shell("cmd package set-home-activity --user 0 ${quote(originalHome)}")
            if (oldServices == null) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${quote(oldServices)}")
            if (oldAccessibilityEnabled == null) shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled ${quote(oldAccessibilityEnabled)}")
            shellAutomation = null
        }
    }

    private fun reportTimings(
        instrumentation: android.app.Instrumentation,
        phase: String,
        homeSamples: List<Long>,
        blockedSamples: List<Long>,
    ) {
        val allSamples = homeSamples + blockedSamples
        val homeText = homeSamples.joinToString(",")
        val blockedText = blockedSamples.joinToString(",")
        val max = allSamples.maxOrNull()
        Log.i(TAG, "phase=$phase homeReturnMs=$homeText blockedReturnMs=$blockedText maxReturnMs=$max")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("frogReturnPhase", phase)
            putString("frogReturnHomeMs", homeText)
            putString("frogReturnBlockedMs", blockedText)
            putLong("frogReturnMaxMs", max ?: 0L)
        })
    }

    private fun resolvedHomePackage(): String? =
        shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lineSequence().map(String::trim).lastOrNull { it.contains('/') && !it.startsWith("priority=") }
            ?.let(ComponentName::unflattenFromString)?.packageName

    private fun chooseBlockedLaunchablePackage(context: Context): String {
        val pm = context.packageManager
        val homePackage = ComponentName.unflattenFromString(
            shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lineSequence().map(String::trim).last { it.contains('/') && !it.startsWith("priority=") },
        )?.packageName
        val candidates = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_ALL,
        ).mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { packageName ->
                packageName != context.packageName && packageName != SETTINGS_PACKAGE && packageName != homePackage &&
                    pm.getLaunchIntentForPackage(packageName) != null && !FrogAppPolicy.isEssential(context, packageName)
            }
        // Calendar has a stable ordinary app window on the stock emulator. A fresh
        // Chrome install's FirstRunActivity may independently relaunch its setup UI.
        return candidates.firstOrNull { it == "com.google.android.calendar" }
            ?: candidates.firstOrNull { it == CHROME_PACKAGE } ?: candidates.firstOrNull()
            ?: throw AssertionError("No safe non-essential launcher app is installed for the blocked-app leg")
    }

    private fun sendHome(action: String) {
        if (action != "swipe") {
            shell("input keyevent KEYCODE_HOME")
            return
        }
        val size = Regex("(\\d+)x(\\d+)").findAll(shell("wm size")).last()
        val width = size.groupValues[1].toInt()
        val height = size.groupValues[2].toInt()
        shell("input swipe ${width / 2} ${height - 40} ${width / 2} ${height * 3 / 5} 200")
    }

    private fun measureUntilResumed(label: String, activityToken: String, command: () -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        command()
        await("$label to resume", MAX_RETURN_MS) { isResumed(activityToken) }
        val resumedAt = SystemClock.elapsedRealtime()
        val actionElapsed = resumedAt - started
        // A cold target app may take seconds before it exposes a window. Measure
        // blocking from the service's actual app detection, through blocker resume.
        // Home timings still include the full key action and launcher transition.
        val observedAt = if (activityToken == BLOCKER_TOKEN && !label.contains("Home")) {
            Regex("lastBlockerObservedAtMs=(\\d+)").find(
                shell("dumpsys activity service com.focuslock.app/.service.AppMonitorAccessibilityService")
            )?.groupValues?.get(1)?.toLong()
                ?: throw AssertionError("Missing native foreground timing for $label")
        } else started
        assertTrue("Expected a fresh foreground observation for $label", observedAt >= started)
        val elapsed = resumedAt - observedAt
        Log.i(TAG, "$label totalActionMs=$actionElapsed")
        Log.i(TAG, "$label resumed in ${elapsed}ms")
        return elapsed
    }

    private fun assertStable(label: String, token: String, durationMs: Long) {
        val end = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < end) {
            val dump = activityDump()
            assertTrue("$label stopped being resumed during the stability window", hasResumed(dump, token))
            if (token == BLOCKER_TOKEN) assertAtMostOneBlockerRecord(dump)
            SystemClock.sleep(100)
        }
    }

    private fun assertAtMostOneBlockerRecord(dump: String) {
        val ids = Regex("ActivityRecord\\{([^}]*BlockerActivity[^}]*)\\}")
            .findAll(dump).map { it.groupValues[1].substringBefore(' ') }.toSet()
        assertEquals("Expected one stable BlockerActivity record; duplicate resumes may have created extras: $ids", 1, ids.size)
    }

    private fun isResumed(token: String): Boolean = hasResumed(activityDump(), token)

    private fun hasResumed(dump: String, token: String): Boolean = dump.lineSequence().any { line ->
        (line.contains("ResumedActivity", ignoreCase = true) || line.contains("topResumedActivity", ignoreCase = true)) &&
            line.contains(token)
    }

    private fun activityDump(): String = shell("dumpsys activity activities")

    private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for $description", condition())
    }

    private fun assertFast(label: String, elapsedMs: Long) {
        assertTrue("$label took ${elapsedMs}ms; threshold is ${MAX_RETURN_MS}ms", elapsedMs <= MAX_RETURN_MS)
    }

    private fun shell(command: String): String {
        val automation = shellAutomation ?: error("UI automation has not been initialized with the fixture flags")
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    // UiAutomation executes argv directly; shell quotes would become literal text.
    private fun quote(value: String): String {
        require(Regex("[A-Za-z0-9._:/-]+").matches(value))
        return value
    }

    companion object {
        private const val TAG = "FrogReturnLatency"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val BLOCKER_TOKEN = "BlockerActivity"
        private const val FROG_HOME_TOKEN = "FrogHomeActivity"
        private const val MAX_RETURN_MS = 1_500L
    }
}
