package com.focuslock.app.ui.permissions

import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Brings the app back to the foreground once the permission the user just opened
 * Android Settings for has been granted, so onboarding can move straight to the next
 * permission. Runs from the accessibility service (exempt from background activity
 * launch restrictions) plus a lightweight in-app fallback poll.
 */
object PermissionReturnWatcher {

    private const val TAG = "PermissionReturn"
    private const val PREFS = "focuslock_permission_flow"
    private const val KEY_KIND = "pending_return_kind"
    private const val KEY_AT = "pending_return_at"

    /** A pending return older than this is stale (user wandered off). */
    private const val PENDING_TIMEOUT_MS = 10 * 60 * 1000L
    /** Fast cadence while we are actively waiting for a grant. */
    private const val POLL_MS = 400L
    /** Slow cadence while nothing is pending; the loop just sleeps. */
    private const val IDLE_POLL_MS = 2000L

    @Volatile
    var appInForeground: Boolean = false
        private set

    /** In-memory mirror of the pending entry so checks never wait on a prefs reload. */
    @Volatile
    private var cachedKind: PermissionKind? = null
    @Volatile
    private var cachedAtMs: Long = 0L
    @Volatile
    private var cacheLoaded = false

    fun markPending(context: Context, kind: PermissionKind) {
        cachedKind = kind
        cachedAtMs = System.currentTimeMillis()
        cacheLoaded = true
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_KIND, kind.name)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark pending return", e)
        }
    }

    fun clearPending(context: Context) {
        cachedKind = null
        cacheLoaded = true
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_KIND)
                .remove(KEY_AT)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear pending return", e)
        }
    }

    fun pending(context: Context): PermissionKind? {
        cachedKind?.let { kind ->
            if (System.currentTimeMillis() - cachedAtMs <= PENDING_TIMEOUT_MS) return kind
            clearPending(context)
            return null
        }
        if (cacheLoaded) return null
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_KIND, null)
            if (raw == null) {
                cacheLoaded = true
                null
            } else {
                val at = prefs.getLong(KEY_AT, 0L)
                if (System.currentTimeMillis() - at > PENDING_TIMEOUT_MS) {
                    clearPending(context)
                    null
                } else {
                    PermissionKind.entries.firstOrNull { it.name == raw }?.also {
                        cachedKind = it
                        cachedAtMs = at
                        cacheLoaded = true
                    } ?: run {
                        clearPending(context)
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read pending return", e)
            null
        }
    }

    fun onAppResumed(context: Context) {
        appInForeground = true
        clearPending(context)
    }

    fun onAppPaused() {
        appInForeground = false
    }

    /**
     * Polls until the pending permission is granted, then brings MainActivity to the
     * front. Safe to run from the accessibility service, which may start activities
     * while the app is in the background.
     */
    fun watch(context: Context, scope: CoroutineScope): Job = scope.launch {
        val appContext = context.applicationContext
        while (isActive) {
            val kind = pending(appContext)
            if (kind == null) {
                delay(IDLE_POLL_MS)
            } else if (PermissionHelper.isGranted(appContext, kind)) {
                bringToFrontIfPending(appContext)
            } else {
                delay(POLL_MS)
            }
        }
    }

    /**
     * In-app fallback for when the accessibility service is not connected yet
     * (API < 29 always allows this; on newer APIs it succeeds when the app holds an
     * exemption such as system overlay). Runs while the dashboard is composed.
     */
    suspend fun fallbackWatch(context: Context) {
        val appContext = context.applicationContext
        while (true) {
            val kind = pending(appContext)
            if (kind == null) {
                delay(IDLE_POLL_MS)
            } else if (PermissionHelper.isGranted(appContext, kind)) {
                bringToFrontIfPending(appContext)
            } else {
                delay(POLL_MS)
            }
        }
    }

    /** Launching activities from the background may be blocked; never crash. */
    @Synchronized
    fun bringToFrontIfPending(context: Context): Boolean {
        val kind = pending(context) ?: return false
        if (!PermissionHelper.isGranted(context, kind)) return false
        clearPending(context)
        if (appInForeground) return true
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring app to front", e)
            false
        }
    }
}
