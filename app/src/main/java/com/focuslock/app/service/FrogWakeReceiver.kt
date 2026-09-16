package com.focuslock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuslock.app.FocusLockApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Belt-and-braces wake trigger for the "eat the frog" lock: arms the day's frog as soon
 * as the device becomes usable around the configured wake hour.
 *
 * The receiver is deliberately self-contained — no state, no retries. It arms at most
 * once per cycle day ([com.focuslock.app.data.repository.FrogRepository.armIfDue] is
 * idempotent) and every failure is swallowed into a log line, so a DataStore hiccup can
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
 *  - `android.intent.action.SCREEN_ON`
 *
 * Notes for the integrator:
 *  - `android.intent.action.BOOT_COMPLETED` additionally requires the
 *    `android.permission.RECEIVE_BOOT_COMPLETED` manifest permission (see snippet above).
 *  - `SCREEN_ON`/`USER_PRESENT` are not delivered to manifest receivers on all API
 *    levels; the recommended path is for the long-lived accessibility service to
 *    register those two at runtime, with this receiver as the belt-and-braces path
 *    (boot, package replace, unlock) — hence the redundancy.
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
                if (repo.armIfDue()) {
                    // Fresh state only for the log line; the accessibility service reads
                    // the state itself after the broadcast below.
                    val state = repo.currentState()
                    Log.i(TAG, "frog armed for cycle ${state.cycleDate}")
                    appContext.sendBroadcast(
                        Intent(ACTION_FROG_ARMED).setPackage(appContext.packageName)
                    )
                }
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
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SCREEN_ON,
        )
    }
}
