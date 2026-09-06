package com.focuslock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val category: String,
    val isSystem: Boolean = false
)

object InstalledAppsRepository {

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

    suspend fun getInstalledLaunchableApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val seen = LinkedHashMap<String, InstalledApp>()
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
            seen.values.sortedWith(compareBy({ it.isSystem }, { it.appName.lowercase() }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    fun getAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString() ?: packageName
        } catch (_: Exception) {
            packageName
        }
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
