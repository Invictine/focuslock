package com.focuslock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val category: String,
    val isSystem: Boolean = false
)

object InstalledAppsRepository {

    /** Memory cache for the launchable-apps list — PM query + label walk is expensive. */
    private const val APPS_CACHE_TTL_MS = 30_000L
    private var cachedApps: List<InstalledApp>? = null
    private var cachedLabelMap: Map<String, String> = emptyMap()
    private var cachedAppsAt: Long = 0L
    private val appsCacheLock = Any()

    /** Labels resolved outside the apps-list cache (e.g. usage rows); process-wide and durable. */
    private val fallbackLabels = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Shared in-memory icon cache so per-row loads don't hit PM + toBitmap on scroll.
     * 192 entries × 96×96 ARGB_8888 ≈ 7 MB worst case (was 512 ≈ 18 MB). RGB_565 was
     * rejected on purpose: adaptive icons keep ~18% transparent margins, and 565 has no
     * alpha — the transparent ring would render black inside the circular row badge
     * (AppSelectorScreen's AppIconBadge draws the bitmap over a colored circle).
     */
    private const val ICON_CACHE_SIZE = 192
    private val iconBitmapCache = object : LruCache<String, ImageBitmap>(ICON_CACHE_SIZE) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }
    private val iconCacheLock = Any()

    /**
     * One shared in-flight load per package (single-flight): concurrent callers await the
     * same deferred instead of receiving a throwaway null — the old boolean flag made a
     * second requester return null and never retry.
     */
    private val iconInFlight =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ImageBitmap?>>()

    // Known doomscroll / social packages for smart categorization (StayFree-style grouping)
    private val SOCIAL_PACKAGES = mapOf(
        "com.instagram.android" to "Social",
        "com.zhiliaoapp.musically" to "Social",
        "com.ss.android.ugc.trill" to "Social",
        "com.reddit.frontpage" to "Social",
        "com.twitter.android" to "Social",
        "com.facebook.katana" to "Social",
        "com.facebook.orca" to "Messaging",
        "com.snapchat.android" to "Social",
        "com.pinterest" to "Social",
        "com.linkedin.android" to "Social",
        "com.facebook.lite" to "Social"
    )

    private val ENTERTAINMENT_PACKAGES = mapOf(
        "com.google.android.youtube" to "Entertainment",
        "com.netflix.mediaclient" to "Entertainment",
        "tv.twitch.android.app" to "Entertainment",
        "com.disney.disneyplus" to "Entertainment",
        "com.amazon.avod.thirdpartyclient" to "Entertainment",
        "com.spotify.music" to "Entertainment",
        "com.google.android.apps.youtube.music" to "Entertainment"
    )

    private val MESSAGING_PACKAGES = mapOf(
        "com.whatsapp" to "Messaging",
        "com.discord" to "Messaging",
        "org.telegram.messenger" to "Messaging",
        "org.telegram.messenger.web" to "Messaging",
        "com.viber.voip" to "Messaging",
        "com.slack" to "Messaging"
    )

    fun categorize(packageName: String, appInfo: ApplicationInfo?): String {
        SOCIAL_PACKAGES[packageName]?.let { return it }
        ENTERTAINMENT_PACKAGES[packageName]?.let { return it }
        MESSAGING_PACKAGES[packageName]?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_GAME -> return "Entertainment"
                else -> {}
            }
        }
        val lower = packageName.lowercase()
        return when {
            lower.contains("browser") || lower.contains("chrome") || lower.contains("firefox") -> "Browser"
            lower.contains("game") -> "Games"
            lower.contains("shop") || lower.contains("amazon") -> "Shopping"
            lower.contains("news") -> "News"
            else -> "Other"
        }
    }

    suspend fun getInstalledLaunchableApps(
        context: Context,
        forceRefresh: Boolean = false
    ): List<InstalledApp> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            synchronized(appsCacheLock) {
                val cached = cachedApps
                if (cached != null && System.currentTimeMillis() - cachedAppsAt < APPS_CACHE_TTL_MS) {
                    return@withContext cached
                }
            }
        }
        val fresh = queryInstalledLaunchableApps(context)
        val labels = fresh.associate { it.packageName to it.appName }
        for ((pkg, label) in labels) fallbackLabels[pkg] = label
        synchronized(appsCacheLock) {
            cachedApps = fresh
            cachedLabelMap = labels
            cachedAppsAt = System.currentTimeMillis()
        }
        fresh
    }

    fun invalidateAppsCache() {
        synchronized(appsCacheLock) {
            cachedApps = null
            cachedLabelMap = emptyMap()
            cachedAppsAt = 0L
        }
    }

    /** Snapshot of the memory cache (may be null when cold). Never hits PackageManager. */
    fun getCachedAppsSnapshot(): List<InstalledApp>? {
        synchronized(appsCacheLock) { return cachedApps }
    }

    /** Cached package -> label map for hot paths (e.g. usage summary) to avoid PM walks. */
    fun getCachedLabelMap(): Map<String, String> {
        val base = synchronized(appsCacheLock) { cachedLabelMap }
        if (fallbackLabels.isEmpty()) return base
        if (base.isEmpty()) return fallbackLabels
        val merged = HashMap<String, String>(base.size + fallbackLabels.size)
        merged.putAll(fallbackLabels)
        merged.putAll(base) // apps-list labels win over fallback labels
        return merged
    }

    private fun queryInstalledLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val seen = LinkedHashMap<String, InstalledApp>()
            // Batch the label walk: resolve each package once, no repeat PM calls.
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                // Skip our own app
                if (pkg == context.packageName) continue
                if (seen.containsKey(pkg)) continue
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo)?.toString() ?: pkg
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    seen[pkg] = InstalledApp(
                        packageName = pkg,
                        appName = label,
                        category = categorize(pkg, appInfo),
                        isSystem = isSystem
                    )
                } catch (_: Exception) {
                    val label = ri.loadLabel(pm)?.toString() ?: pkg
                    seen[pkg] = InstalledApp(pkg, label, categorize(pkg, null))
                }
            }
            // Sort: user apps first alphabetically, then system apps
            return seen.values.sortedWith(compareBy({ it.isSystem }, { it.appName.lowercase() }))
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cached icon bitmap for Compose rows. Returns the memory-cached bitmap when
     * present; otherwise loads the drawable + toBitmap on IO and caches by package.
     * Concurrent callers for the same package await one shared in-flight load (or
     * receive its real result) — never a null that discards the work in progress.
     * Must be called from a background dispatcher.
     */
    suspend fun getAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
        synchronized(iconCacheLock) {
            iconBitmapCache.get(packageName)?.let { return it }
        }

        val existing = iconInFlight[packageName]
        if (existing != null) return awaitIconLoad(packageName, existing)

        val load = kotlinx.coroutines.CompletableDeferred<ImageBitmap?>()
        val raced = iconInFlight.putIfAbsent(packageName, load)
        if (raced != null) return awaitIconLoad(packageName, raced)

        try {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val drawable: Drawable? = getAppIcon(context.applicationContext, packageName)
                    drawable?.toBitmap(width = 96, height = 96)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp != null) {
                synchronized(iconCacheLock) { iconBitmapCache.put(packageName, bmp) }
            }
            load.complete(bmp)
            return bmp
        } catch (e: kotlinx.coroutines.CancellationException) {
            load.complete(null) // awaiters fall back to the letter badge; next call retries
            throw e
        } catch (_: Throwable) {
            load.complete(null)
            return null
        } finally {
            iconInFlight.remove(packageName, load)
        }
    }

    /** Awaits a shared in-flight icon load; prefers the cache once it lands. */
    private suspend fun awaitIconLoad(
        packageName: String,
        load: kotlinx.coroutines.CompletableDeferred<ImageBitmap?>
    ): ImageBitmap? {
        val awaited = try { load.await() } catch (_: Exception) { null }
        synchronized(iconCacheLock) { iconBitmapCache.get(packageName) }?.let { return it }
        return awaited
    }

    fun getCachedIconBitmap(packageName: String): ImageBitmap? {
        synchronized(iconCacheLock) { return iconBitmapCache.get(packageName) }
    }

    fun preloadIconBitmaps(bitmaps: Map<String, ImageBitmap>) {
        synchronized(iconCacheLock) {
            for ((pkg, bmp) in bitmaps) iconBitmapCache.put(pkg, bmp)
        }
    }

    fun getAppLabel(context: Context, packageName: String): String {
        fallbackLabels[packageName]?.let { return it }
        synchronized(appsCacheLock) { cachedLabelMap[packageName] }?.let { return it }
        val label = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString() ?: packageName
        } catch (_: Exception) {
            packageName
        }
        fallbackLabels[packageName] = label
        return label
    }

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
