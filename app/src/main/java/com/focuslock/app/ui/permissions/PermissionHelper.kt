package com.focuslock.app.ui.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.focuslock.app.service.AppMonitorAccessibilityService
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.service.UsageTrackerHelper

object PermissionHelper {

    private const val TAG = "PermissionHelper"

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

    fun openAccessibilitySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openUsageAccessSettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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
        safeStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            safeStart(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    }

    fun openAppNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeStart(
                context,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
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
