package com.focuslock.app.ui.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.focuslock.app.service.AppMonitorAccessibilityService
import com.focuslock.app.service.FocusLockDeviceAdminReceiver
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.service.UsageTrackerHelper

enum class PermissionKind {
    ACCESSIBILITY,
    USAGE,
    OVERLAY,
    NOTIFICATION_LISTENER,
    BATTERY,
    DEVICE_ADMIN,
    POST_NOTIFICATIONS
}

object PermissionHelper {

    private const val TAG = "PermissionHelper"

    // Legacy extras (pre-API-26 app-notification-settings form). Kept as a last-resort
    // candidate for OEM builds that still read the old keys.
    private const val LEGACY_EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
    private const val LEGACY_EXTRA_APP_UID = "android.provider.extra.APP_UID"

    fun deviceAdminComponent(context: Context): ComponentName =
        ComponentName(context, FocusLockDeviceAdminReceiver::class.java)

    // ---------- Checks ----------

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val expected = ComponentName(context, AppMonitorAccessibilityService::class.java)
            val expectedFlat = expected.flattenToString()
            enabledServices.any { info ->
                val id = info.id ?: return@any false
                // Exact component match first; the package+class fallback tolerates OEMs
                // that store the short form. Never match another app's service by suffix.
                id == expectedFlat ||
                    id == expected.flattenToShortString() ||
                    (id.contains(expected.packageName) && id.contains(expected.className))
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isUsageAccessGranted(context: Context): Boolean {
        return UsageTrackerHelper.hasUsageStatsPermission(context)
    }

    fun isOverlayGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        return try {
            // Colon-separated list of flattened ComponentNames; parse each entry instead
            // of string-matching the class name (brittle to renames and false positives).
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val expected = ComponentName(context, TickTickNotificationListener::class.java)
            flat.split(':').any { entry ->
                val parsed = ComponentName.unflattenFromString(entry)
                if (parsed != null) {
                    parsed == expected
                } else {
                    entry.contains(expected.packageName) && entry.contains(expected.className)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(deviceAdminComponent(context))
        } catch (_: Exception) {
            false
        }
    }

    fun isPostNotificationsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isGranted(context: Context, kind: PermissionKind): Boolean = when (kind) {
        PermissionKind.ACCESSIBILITY -> isAccessibilityServiceEnabled(context)
        PermissionKind.USAGE -> isUsageAccessGranted(context)
        PermissionKind.OVERLAY -> isOverlayGranted(context)
        PermissionKind.NOTIFICATION_LISTENER -> isNotificationListenerGranted(context)
        PermissionKind.BATTERY -> isBatteryOptimizationIgnored(context)
        PermissionKind.DEVICE_ADMIN -> isDeviceAdminActive(context)
        PermissionKind.POST_NOTIFICATIONS -> isPostNotificationsGranted(context)
    }

    fun getMissingPermissions(context: Context): List<PermissionKind> =
        PermissionKind.entries.filter { !isGranted(context, it) }

    fun getNextMissingPermission(context: Context): PermissionKind? =
        getMissingPermissions(context).firstOrNull()

    // ---------- Openers (most-specific first, with fallback) ----------

    fun openAccessibilitySettings(context: Context) {
        // Direct settings page is the most specific stable API; try the highlighted
        // entry first, then the plain page, then this app's info page.
        val highlighted = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "focuslock_accessibility")
            putExtra(":settings:show_fragment_title", "FocusLock")
        }
        if (launchFirstResolvable(context, highlighted, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun openUsageAccessSettings(context: Context) {
        // Usage-access list first (many builds accept the package name as the highlight
        // key), then the app-info page as a last resort.
        val highlighted = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", context.packageName)
        }
        if (launchFirstResolvable(context, highlighted, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun openOverlaySettings(context: Context) {
        // Per-app overlay page first, then the generic overlay list, then app info.
        val perApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (launchFirstResolvable(context, perApp, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun openNotificationListenerSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ per-app detail page. It REQUIRES the flattened ComponentName of our
            // listener service in EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME: without it,
            // Settings' NotificationAccessDetails unflattens null and the Settings app
            // itself throws the NPE this method used to trigger. The package extra alone
            // is NOT a substitute on these builds.
            val listener = ComponentName(context, TickTickNotificationListener::class.java)
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
                // Some OEM builds additionally read the package extra to highlight the row.
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            if (launchFirstResolvable(context, detail)) return
        }
        // Generic notification-access list screen: exists on every API and is always the
        // safe fallback (the user flips the FocusLock toggle manually).
        if (launchFirstResolvable(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            return
        }
        // App notification settings (API 26+), then the app-info page.
        if (launchFirstResolvable(
                context,
                appNotificationSettingsIntent(context),
                legacyAppNotificationSettingsIntent(context)
            )
        ) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun openBatteryOptimizationSettings(context: Context) {
        // Direct exemption prompt first (uses the declared REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        // permission), then the battery-optimization list, then app info.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (launchFirstResolvable(context, direct, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun openAppNotificationSettings(context: Context) {
        if (launchFirstResolvable(
                context,
                appNotificationSettingsIntent(context),
                legacyAppNotificationSettingsIntent(context)
            )
        ) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun openDeviceAdminSettings(context: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents accidental or impulsive uninstalls during a binge. " +
                    "Remove anytime via Settings > Disable admin."
            )
        }
        if (launchFirstResolvable(context, intent, Intent(Settings.ACTION_SECURITY_SETTINGS))) {
            return
        }
        launchFirstResolvable(context, appDetailsIntent(context))
    }

    fun disableDeviceAdmin(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(deviceAdminComponent(context))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove device admin", e)
        }
    }

    fun openPermissionWithHighlight(context: Context, kind: PermissionKind) {
        // Remember which permission the user is about to grant so the return watcher can
        // bring FocusLock back to the front automatically once it is granted.
        PermissionReturnWatcher.markPending(context, kind)
        when (kind) {
            PermissionKind.ACCESSIBILITY -> openAccessibilitySettings(context)
            PermissionKind.USAGE -> openUsageAccessSettings(context)
            PermissionKind.OVERLAY -> openOverlaySettings(context)
            PermissionKind.NOTIFICATION_LISTENER -> openNotificationListenerSettings(context)
            PermissionKind.BATTERY -> openBatteryOptimizationSettings(context)
            PermissionKind.DEVICE_ADMIN -> openDeviceAdminSettings(context)
            PermissionKind.POST_NOTIFICATIONS -> openAppNotificationSettings(context)
        }
    }

    // ---------- Launch helpers ----------

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    private fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    private fun legacyAppNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(LEGACY_EXTRA_APP_PACKAGE, context.packageName)
            putExtra(LEGACY_EXTRA_APP_UID, context.applicationInfo.uid)
        }

    /**
     * Launches the first candidate that both resolves and starts successfully, in order.
     * Returns true when a settings screen was opened. Never throws: unresolvable and
     * failed candidates are logged and skipped.
     */
    private fun launchFirstResolvable(context: Context, vararg candidates: Intent): Boolean {
        for (intent in candidates) {
            if (!canResolve(context, intent)) continue
            if (startSafely(context, intent)) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun canResolve(context: Context, intent: Intent): Boolean = try {
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve: ${intent.action}", e)
        false
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        // The caller may be a non-Activity context (Service / Application); those need
        // NEW_TASK to launch a Settings activity.
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Settings screen not found: ${intent.action}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open: ${intent.action}", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open: ${intent.action}", e)
            false
        }
    }
}
