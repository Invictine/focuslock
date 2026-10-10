package com.focuslock.app.service

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.focuslock.app.ui.blocker.FrogHomeActivity

/** Android routes Home directly here; the saved launcher is used outside Frog time. */
object FrogHomeLauncher {
    private const val PREFS = "frog_home_launcher"
    private const val FALLBACK = "previous_home_component"
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun isDefault(context: Context): Boolean =
        context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { it.packageName == context.packageName && it.name == FrogHomeActivity::class.java.name } == true

    fun captureFallback(context: Context) {
        val component = currentDefaultComponent(context) ?: return
        val componentName = component.flattenToString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(FALLBACK, null)
        if (!shouldCaptureFallback(componentName, saved, context.packageName)) return
        if (componentName !in validHomeCandidates(context)) return
        prefs.edit().putString(FALLBACK, componentName).apply()
    }

    /** Avoid a full launcher inventory query when the current fallback is already known. */
    internal fun shouldCaptureFallback(current: String?, saved: String?, appPackage: String): Boolean =
        current != null && current.substringBefore('/') != appPackage && current != saved

    private fun fallbackComponent(context: Context): ComponentName? {
        val validCandidates = validHomeCandidates(context)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FALLBACK, null)
        val current = currentDefaultComponent(context)?.flattenToString()
        return chooseRegularHome(saved, current, validCandidates)
            ?.let(ComponentName::unflattenFromString)
    }

    /** Pure selection policy, kept internal so saved/current/ambiguous cases can be tested. */
    internal fun chooseRegularHome(
        saved: String?,
        current: String?,
        validCandidates: Set<String>
    ): String? = when {
        saved != null && saved in validCandidates -> saved
        current != null && current in validCandidates -> current
        else -> validCandidates.singleOrNull()
    }

    private fun currentDefaultComponent(context: Context): ComponentName? =
        context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { ComponentName(it.packageName, it.name) }

    private fun validHomeCandidates(context: Context): Set<String> =
        context.packageManager.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                val component = ComponentName(info.packageName, info.name)
                if (info.packageName == context.packageName || isSystemResolver(component) ||
                    !info.enabled || !info.applicationInfo.enabled || !info.exported
                ) return@mapNotNull null
                component.flattenToString()
            }
            .toSet()

    private fun isSystemResolver(component: ComponentName): Boolean =
        component.packageName == "android" ||
            (component.packageName == "com.android.intentresolver" && component.className.endsWith("ResolverActivity"))

    // The event path needs only identity; validate the component when actually opening Home.
    fun fallbackPackage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FALLBACK, null)
            ?.let(ComponentName::unflattenFromString)?.packageName?.takeUnless { it == context.packageName }

    fun openRegularHome(activity: Activity): Boolean {
        val component = fallbackComponent(activity) ?: return false
        return runCatching {
            activity.startActivity(homeIntent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun requestDefault(activity: Activity) {
        captureFallback(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                activity.startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }
}
