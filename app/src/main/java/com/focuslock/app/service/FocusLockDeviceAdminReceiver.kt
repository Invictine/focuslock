package com.focuslock.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

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
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.i(TAG, "Device admin disable requested")
        return "Disabling lets FocusLock be uninstalled. Sure?"
    }

    companion object {
        private const val TAG = "FocusLockDeviceAdmin"
    }
}
