package com.focuslock.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

private val Context.permanentBlocksDataStore by preferencesDataStore(name = "focuslock_permanent_app_blocks")

/**
 * Device-local, append-only store for apps that should always be blocked.
 *
 * This deliberately has no expiry, credit, session, emergency, or in-app remove
 * operation. A separate store keeps a sync restore or a normal boundary edit from
 * silently weakening a user's permanent commitment.
 */
class PermanentBlocksRepository(private val context: Context) {
    private object Keys {
        val PACKAGES = stringSetPreferencesKey("packages")
    }

    private val mirror = Collections.synchronizedSet(mutableSetOf<String>())
    private val warmMutex = Mutex()
    @Volatile private var hasLoaded = false

    val packagesFlow: Flow<Set<String>> = context.permanentBlocksDataStore.data
        // Keep the last known set if DataStore has a transient read failure. Clearing
        // it here would turn a permanent commitment into a temporary fail-open block.
        .catch { emit(mutablePreferencesOf(Keys.PACKAGES to synchronized(mirror) { mirror.toSet() })) }
        .map { prefs ->
            val value = prefs[Keys.PACKAGES].orEmpty().map(::normalize).filter(String::isNotEmpty).toSet()
            synchronized(mirror) {
                mirror.clear()
                mirror.addAll(value)
            }
            hasLoaded = true
            value
        }

    /** Starts the DataStore collector and warms the synchronous enforcement mirror. */
    suspend fun warm() {
        if (hasLoaded) return
        warmMutex.withLock {
            if (!hasLoaded) packagesFlow.first()
        }
    }

    fun isPermanentlyBlocked(packageName: String): Boolean =
        synchronized(mirror) { normalize(packageName) in mirror }

    /** Idempotent. Returns false when the package is protected and cannot be added. */
    suspend fun add(packageName: String): Boolean {
        val normalized = normalize(packageName)
        if (normalized.isEmpty() || isProtectedPackage(context, normalized)) return false
        context.permanentBlocksDataStore.edit { prefs ->
            val current = prefs[Keys.PACKAGES].orEmpty().toMutableSet()
            current += normalized
            prefs[Keys.PACKAGES] = current
            synchronized(mirror) { mirror += normalized }
        }
        return true
    }

    /** Permanent blocks intentionally cannot be removed by the app UI. */
    fun canRemoveInApp(): Boolean = false

    private fun normalize(value: String): String = value.trim().lowercase()

    companion object {
        /** Packages which must never be permanently blocked because they are recovery paths. */
        fun isProtectedPackage(context: Context, packageName: String): Boolean {
            val own = context.packageName
            if (packageName == own || packageName == "android" || packageName == "com.android.systemui" ||
                packageName == "com.android.settings" || packageName == "com.android.dialer" ||
                packageName == "com.google.android.dialer" || packageName == "com.samsung.android.dialer") return true
            val pm = context.packageManager
            val launcher = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            if (packageName == launcher) return true
            val defaultDialer = try {
                (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
            } catch (_: SecurityException) { null }
            if (packageName == defaultDialer) return true
            val enabledIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            return packageName == enabledIme || packageName == "com.android.packageinstaller" ||
                packageName == "com.google.android.packageinstaller"
        }
    }
}

/** Pure enforcement rule used by the service and JVM tests. */
internal object PermanentBlockPolicy {
    fun shouldEnforce(permanent: Boolean, protected: Boolean): Boolean = permanent && !protected

    fun shouldEnforce(packageName: String?, permanentPackages: Set<String>, protectedPackages: Set<String>): Boolean {
        val normalized = packageName?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return shouldEnforce(
            permanent = normalized in permanentPackages.map { it.trim().lowercase() }.toSet(),
            protected = normalized in protectedPackages.map { it.trim().lowercase() }.toSet(),
        )
    }
}
