package com.focuslock.app.service

/** Keeps the exact update client available when FocusLock needs an update or repair. */
object AppUpdateAccessPolicy {
    // Verified from the user's App Tester APK manifest, not the Firebase SDK namespace.
    const val APP_TESTER_PACKAGE = "dev.firebase.appdistribution"

    fun isUpdateApp(packageName: String?): Boolean = packageName == APP_TESTER_PACKAGE
}
