package com.focuslock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.pendingFrogGraceDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Schedules the daily Frog wake and starts the local grace timer after device unlock.
 *
 * The receiver is deliberately self-contained — no state, no retries. It arms at most
 * once per cycle day and every failure is swallowed into a log line, so a DataStore hiccup can
 * never crash the process (a crash on BOOT_COMPLETED would look like a boot loop).
 *
 * ## Manifest wiring (declared by the integrator, not in this file)
 *
 * ```xml
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * <receiver
 *     android:name=".service.FrogWakeReceiver"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.intent.action.USER_PRESENT" />
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *         <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *         <action android:name="android.intent.action.SCREEN_ON" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * Actions handled (anything else is ignored):
 *  - `android.intent.action.USER_PRESENT`
 *  - `android.intent.action.BOOT_COMPLETED`
 *  - `android.intent.action.MY_PACKAGE_REPLACED`
 *  - `android.intent.action.SCREEN_ON` (schedules only; it does not start grace)
 *
 * Notes for the integrator:
 *  - `android.intent.action.BOOT_COMPLETED` additionally requires the
 *    `android.permission.RECEIVE_BOOT_COMPLETED` manifest permission (see snippet above).
 *  - `SCREEN_ON`/`USER_PRESENT` are not delivered to manifest receivers on all API
 *    levels; the accessibility service also registers them at runtime. Only USER_PRESENT
 *    starts grace, since screen-on can occur while the device remains locked.
 *  - When this receiver actually arms the frog it re-broadcasts
 *    [FrogWakeReceiver.ACTION_FROG_ARMED] inside the app package so the running
 *    accessibility service can re-evaluate the foreground app immediately.
 */
class FrogWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Safe call: if the Application/repository has not been initialized yet,
                // this throws (lateinit); the surrounding try/catch logs and swallows it.
                val repo = FocusLockApplication.instance?.frogRepository ?: return@launch
                if (action == FrogMorningScheduler.ACTION_FROG_GRACE_EXPIRED) {
                    if (repo.armAfterGraceIfDue()) {
                        appContext.sendBroadcast(Intent(ACTION_FROG_ARMED).setPackage(appContext.packageName))
                    }
                } else if (action == Intent.ACTION_USER_PRESENT) {
                    if (repo.startGraceIfDue()) {
                        appContext.sendBroadcast(Intent(ACTION_FROG_ARMED).setPackage(appContext.packageName))
                    }
                }
                // Restore the persisted pending deadline after boot and clock/time-zone
                // changes. Also catch an overdue deadline if the service was stopped.
                if (action != FrogMorningScheduler.ACTION_FROG_GRACE_EXPIRED && repo.armAfterGraceIfDue()) {
                    appContext.sendBroadcast(Intent(ACTION_FROG_ARMED).setPackage(appContext.packageName))
                }
                val state = repo.currentState()
                val graceDeadline = pendingFrogGraceDeadline(
                    enabled = state.enabled,
                    armed = state.armed,
                    deadlineMillis = state.graceEndsAtMillis,
                    nowMillis = System.currentTimeMillis(),
                )
                FrogMorningScheduler.scheduleGraceExpiry(appContext, graceDeadline)
                // Re-arm the next daily wake after boot, package replacement, time changes,
                // and every interaction; merely reaching morning never starts grace.
                FrogMorningScheduler.schedule(appContext, repo.wakeHourFlow.first(), repo.enabledFlow.first())
            } catch (t: Throwable) {
                // Swallow everything: a receiver must never crash the process.
                Log.w(TAG, "frog wake handling failed", t)
            } finally {
                // goAsync() must be finished on every path or the system kills us.
                withContext(NonCancellable) { pendingResult.finish() }
            }
        }
    }

    companion object {
        private const val TAG = "FrogWakeReceiver"

        /** In-app broadcast telling the accessibility service to re-evaluate now. */
        const val ACTION_FROG_ARMED = "com.focuslock.app.action.FROG_ARMED"

        /** Only these system actions may trigger an arm attempt. */
        private val SUPPORTED_ACTIONS = setOf(
            "com.focuslock.app.action.FROG_MORNING_ALARM",
            FrogMorningScheduler.ACTION_FROG_GRACE_EXPIRED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SCREEN_ON,
        )
    }
}
