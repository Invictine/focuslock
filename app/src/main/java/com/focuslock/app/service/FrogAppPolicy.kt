package com.focuslock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import com.focuslock.app.data.model.FrogState

/** Frog allows only essential functions and the tools confirmed for this cycle. */
object FrogAppPolicy {
    private val corePackages = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.android.server.telecom", "com.android.emergency",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.ticktick.task",
    )

    fun shouldBlock(
        packageName: String,
        locked: Boolean,
        toolsConfirmed: Boolean,
        allowedTools: Set<String>,
        essentials: Set<String>,
    ): Boolean = locked && packageName !in essentials &&
        (!toolsConfirmed || packageName !in allowedTools)

    /** The mandatory, pinned Frog shortcuts (TickTick, Phone, Clock and Messages). */
    fun essentialLaunchPackages(context: Context): Set<String> =
        defaultLaunchPackages(context).take(4).toSet()

    /** Effective ordered shortcut list: mandatory core four plus configured options. */
    fun configuredLaunchPackages(context: Context, state: FrogState): List<String> =
        configuredLaunchPackages(defaultLaunchPackages(context), state.essentialAppPackages)

    const val GPAY_PACKAGE = "com.google.android.apps.nbu.paisa.user"
    const val GPAY_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
    val GOOGLE_PAY_PACKAGES = setOf(GPAY_PACKAGE, GPAY_WALLET_PACKAGE)

    /** Resolves the installed Google Pay or Google Wallet package, defaulting to Google Pay. */
    fun resolveGooglePay(context: Context): String = runCatching {
        val pm = context.packageManager
        when {
            pm.getLaunchIntentForPackage(GPAY_PACKAGE) != null -> GPAY_PACKAGE
            pm.getLaunchIntentForPackage(GPAY_WALLET_PACKAGE) != null -> GPAY_WALLET_PACKAGE
            else -> GPAY_PACKAGE
        }
    }.getOrDefault(GPAY_PACKAGE)

    /** Pure ordering helper: null selects all default eight; empty selects only pinned core four. */
    fun configuredLaunchPackages(defaults: List<String>, configuredPackages: Set<String>?): List<String> {
        val core = defaults.take(4)
        val configured = configuredPackages ?: defaults.toSet()
        val defaultOptionals = defaults.drop(4).filter { it in configured }
        val additional = (configured - defaults.toSet()).sorted()
        return (core + defaultOptionals + additional).distinct()
    }

    /** Effective policy set of apps kept available during this Frog cycle. */
    fun essentialLaunchPackages(context: Context, state: FrogState): Set<String> =
        configuredLaunchPackages(context, state).toSet()

    /** Stable ordered Frog shortcuts; resolved defaults replace fallback packages. */
    fun defaultLaunchPackages(context: Context): List<String> {
        val dialer = resolve(context, Intent(Intent.ACTION_DIAL)) ?: "com.android.dialer"
        val clock = resolve(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)) ?: "com.google.android.deskclock"
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?: resolve(context, Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")))
            ?: "com.google.android.apps.messaging"
        val gpay = resolveGooglePay(context)
        return listOf("com.ticktick.task", dialer, clock, sms, "com.whatsapp", "com.openai.chatgpt", "com.spotify.music", gpay)
            .filter { it.isNotBlank() }.distinct()
    }

    fun isHome(context: Context, packageName: String): Boolean =
        packageName == resolve(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) ||
            packageName == FrogHomeLauncher.fallbackPackage(context)

    /** Whether the active Frog policy should put the focus screen over this package. */
    fun shouldShowFocusScreen(context: Context, packageName: String, state: FrogState): Boolean =
        packageName != context.packageName && state.locked &&
            (isHome(context, packageName) || isBlocked(context, packageName, state))

    fun shouldDeferMorningPrompt(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName || isHome(context, packageName)) return false
        if (!isEssential(context, packageName)) return false
        val dialer = resolve(context, Intent(Intent.ACTION_DIAL))
        return packageName == dialer || packageName !in essentialLaunchPackages(context)
    }

    /** Apps that remain available regardless of the user's optional Frog selection. */
    fun isEssential(context: Context, packageName: String): Boolean =
        isSafetyEssential(context, packageName)

    /** Safety/recovery exemptions that apply outside Frog as well. */
    fun isSafetyEssential(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName || packageName in corePackages) return true
        if (isHome(context, packageName)) return true
        if (packageName in defaultLaunchPackages(context).take(4)) return true
        val ime = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
        return packageName == ime
    }

    fun isBlocked(context: Context, packageName: String, state: FrogState): Boolean {
        val essentials = essentialLaunchPackages(context, state)
        val paymentExemption = if (GOOGLE_PAY_PACKAGES.any { it in essentials } && packageName in GOOGLE_PAY_PACKAGES) {
            setOf(packageName)
        } else {
            emptySet()
        }
        return state.locked && shouldBlock(
            packageName = packageName,
            locked = state.locked,
            toolsConfirmed = state.toolsConfirmed,
            allowedTools = state.allowedToolPackages,
            essentials = essentials + paymentExemption +
                if (isSafetyEssential(context, packageName)) setOf(packageName) else emptySet(),
        )
    }

    private fun resolve(context: Context, intent: Intent): String? = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeUnless { it == "android" }
    }.getOrNull()
}
