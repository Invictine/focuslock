package com.focuslock.app.service

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** Narrow package exemptions for Android controls needed to recover and operate the device. */
object DeviceAccessPolicy {
    private val corePackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** Pure exact-match policy. Registered keyboards are passed independently of the active IME. */
    fun isExempt(packageName: String, ownPackage: String, registeredImePackages: Set<String>): Boolean =
        packageName.isNotBlank() &&
            ((ownPackage.isNotBlank() && packageName == ownPackage) ||
                packageName in corePackages || packageName in registeredImePackages)

    /** Reads all registered IMEs, with the secure default as a fallback if enumeration fails. */
    fun isExempt(context: Context, packageName: String): Boolean {
        val own = runCatching { context.packageName }.getOrNull().orEmpty()
        if (isExempt(packageName, own, emptySet())) return true
        return isExempt(packageName, own, keyboardPackages(context))
    }

    /** All registered input methods, regardless of which one is currently active. */
    fun keyboardPackages(context: Context): Set<String> {
        val registered = runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.inputMethodList
                ?.mapNotNull { it.packageName?.takeIf(String::isNotBlank) }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
        val secureDefault = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf(String::isNotBlank)
        }.getOrNull()
        return registered + listOfNotNull(secureDefault)
    }
}
