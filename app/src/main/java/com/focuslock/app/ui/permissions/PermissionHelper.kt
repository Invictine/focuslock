package com.focuslock.app.ui.permissions

import android.accessibilityservice.AccessibilityServiceInfo
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

    fun deviceAdminComponent(context: Context): ComponentName =
        ComponentName(context, FocusLockDeviceAdminReceiver::class.java)

    // ---------- Checks ----------

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceName = ComponentName(context, AppMonitorAccessibilityService::class.java).flattenToString()
        return enabledServices.any { it.id == expectedServiceName || it.id.contains("AppMonitorAccessibilityService") }
    }

    fun isUsageAccessGranted(context: Context): Boolean {
        return UsageTrackerHelper.hasUsageStatsPermission(context)
    }

    fun isOverlayGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val component = ComponentName(context, TickTickNotificationListener::class.java).flattenToString()
        return flat?.contains(component) == true || flat?.contains("TickTickNotificationListener") == true
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
        // Direct settings page is the most specific stable API; try highlighted
        // extras first, fall back to the plain page.
        val highlighted = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "focuslock_accessibility")
            putExtra(":settings:show_fragment_title", "FocusLock")
        }
        if (!tryStart(context, highlighted)) {
            safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    fun openUsageAccessSettings(context: Context) {
        // Per-app detail screen first, then the generic usage-access list.
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (!tryStart(context, detail)) {
            safeStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    fun openOverlaySettings(context: Context) {
        safeStart(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun openNotificationListenerSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ supports a per-app listener detail page.
            val detail = try {
                Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS").apply {
                    putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                }
            } catch (_: Exception) { null }
            if (detail != null && tryStart(context, detail)) return
            // Some OEMs honor the generic extra key instead.
            val compat = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
            }
            if (tryStart(context, compat)) return
        }
        safeStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (!tryStart(context, direct)) {
                safeStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    fun openAppNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            if (!tryStart(context, direct)) {
                safeStart(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
            }
        }
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
        safeStart(context, intent)
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

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeStart(context: Context, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Settings screen not found: ${intent.action}", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open: ${intent.action}", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open: ${intent.action}", e)
        }
    }
}
