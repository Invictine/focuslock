package com.focuslock.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.focuslock.app.reminder.RemovalReminderController

/**
 * Minimal device-admin receiver used only for anti-uninstall friction.
 * No lock/wipe policies are used — force-lock policy only.
 */
class FocusLockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        Toast.makeText(context, "Uninstall protection enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
        Toast.makeText(context, "Uninstall protection disabled", Toast.LENGTH_SHORT).show()
        RemovalReminderController.show(context, "admin_disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.i(TAG, "Device admin disable requested")
        RemovalReminderController.show(context, "admin_requested")
        return "Do you really want to do this? Disabling device admin lets you uninstall FocusLock and removes this protection. You can cancel to keep it enabled."
    }

    companion object {
        private const val TAG = "FocusLockDeviceAdmin"
    }
}
