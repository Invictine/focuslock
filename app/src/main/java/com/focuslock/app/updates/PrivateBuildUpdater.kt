package com.focuslock.app.updates

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.focuslock.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.google.firebase.appdistribution.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PrivateBuildUpdateStatus(
    val checking: Boolean = false,
    val message: String = "Updates are checked when you open FocusLock.",
)

/** Google tester consent, release alert, download, and Android installer for private builds. */
internal object PrivateBuildUpdater {
    // Main-thread, process-wide state survives activity recreation and SDK UI returns.
    private val gate = UpdateCheckGate()
    private val mutableStatus = MutableStateFlow(PrivateBuildUpdateStatus())
    val status = mutableStatus.asStateFlow()
    private var distribution: FirebaseAppDistribution? = null

    fun initialize(application: Application) {
        if (!BuildConfig.PRIVATE_BUILD_UPDATES) return
        if (listOf(
                BuildConfig.DISTRIBUTION_APP_ID,
                BuildConfig.DISTRIBUTION_API_KEY,
                BuildConfig.DISTRIBUTION_PROJECT_ID,
                BuildConfig.DISTRIBUTION_SENDER_ID,
            ).any { it.isBlank() }) {
            mutableStatus.value = PrivateBuildUpdateStatus(message = "This build is missing update configuration. Open App Tester to update.")
            return
        }
        try {
            val firebase = FirebaseApp.getApps(application).find { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(application, FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.DISTRIBUTION_APP_ID)
                    .setApiKey(BuildConfig.DISTRIBUTION_API_KEY)
                    .setProjectId(BuildConfig.DISTRIBUTION_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.DISTRIBUTION_SENDER_ID)
                    .build())
            // Never query another Firebase app's private releases.
            if (firebase.options.applicationId != BuildConfig.DISTRIBUTION_APP_ID) {
                mutableStatus.value = PrivateBuildUpdateStatus(message = "Update configuration does not match this app. Open App Tester to update.")
                Log.w(TAG, "Private update configuration does not match the Firebase app")
                return
            }
            // Register before activity events, so Google's consent and installer UI
            // can find the foreground activity on the first launch.
            distribution = FirebaseAppDistribution.getInstance()
        } catch (_: Exception) {
            mutableStatus.value = PrivateBuildUpdateStatus(message = "Update service could not start. Open App Tester to update.")
            Log.w(TAG, "Private update service could not start")
        }
    }

    fun onResume(activity: Activity) {
        // Let Android deliver onActivityResumed to the SDK before showing its UI.
        activity.window.decorView.post { check(activity, manual = false) }
    }

    fun checkNow(activity: Activity) = check(activity, manual = true)

    private fun check(activity: Activity, manual: Boolean) {
        if (!BuildConfig.PRIVATE_BUILD_UPDATES || activity.isFinishing || activity.isDestroyed) return
        if (activity is LifecycleOwner && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val sdk = distribution ?: return // Configuration/startup error remains visible.
        if (!gate.tryStart(SystemClock.elapsedRealtime(), manual)) return
        mutableStatus.value = PrivateBuildUpdateStatus(checking = true, message = "Checking for updates…")
        try {
            sdk.updateIfNewReleaseAvailable()
                .addOnProgressListener { progress ->
                    val message = when (progress.updateStatus) {
                        UpdateStatus.DOWNLOADING -> {
                            val total = progress.apkFileTotalBytes
                            if (total > 0) "Downloading update: ${((progress.apkBytesDownloaded.toDouble() / total) * 100).toInt().coerceIn(0, 100)}%"
                            else "Downloading update…"
                        }
                        UpdateStatus.DOWNLOADED -> "Update downloaded. Confirm installation in Android."
                        UpdateStatus.NEW_RELEASE_NOT_AVAILABLE -> "You have the latest build available to your tester account."
                        UpdateStatus.INSTALL_CANCELED, UpdateStatus.UPDATE_CANCELED -> "Update cancelled. Tap Check for updates to retry."
                        UpdateStatus.DOWNLOAD_FAILED -> "Download failed. Check your connection and retry."
                        UpdateStatus.INSTALL_FAILED -> "Installation failed. Open App Tester to retry."
                        UpdateStatus.NEW_RELEASE_CHECK_FAILED -> "Update check failed. Check your connection and retry."
                        UpdateStatus.REDIRECTED_TO_PLAY -> "Complete the update in Google Play."
                        else -> "Checking for updates…"
                    }
                    mutableStatus.value = PrivateBuildUpdateStatus(checking = true, message = message)
                }
                .addOnFailureListener { error ->
                    // Exception bodies can contain auth/download URLs; log only the enum.
                    val code = (error as? FirebaseAppDistributionException)?.errorCode
                    Log.w(TAG, "Private update check did not complete (status=$code)")
                    mutableStatus.value = PrivateBuildUpdateStatus(message = updateFailureMessage(code))
                }
                .addOnCompleteListener {
                    gate.finish()
                    val current = mutableStatus.value
                    mutableStatus.value = current.copy(checking = false,
                        message = if (it.isSuccessful && current.message == "Checking for updates…")
                            "Update check complete." else current.message)
                }
        } catch (_: Exception) {
            gate.finish()
            mutableStatus.value = PrivateBuildUpdateStatus(message = "Update check could not start. Open App Tester to update.")
            Log.w(TAG, "Private update check could not start")
        }
    }

    private const val TAG = "PrivateBuildUpdater"
}
