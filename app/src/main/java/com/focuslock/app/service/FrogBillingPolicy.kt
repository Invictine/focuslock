package com.focuslock.app.service

/** Identifies Play Store windows used for the official Google Play billing flow. */
object FrogBillingPolicy {
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val BILLING_ACTIVITY_PREFIX = "com.google.android.finsky.billing."

    /**
     * Play Store itself remains subject to Frog. Only its official billing activity
     * windows are exempt, since purchase redirects can leave these over TickTick.
     */
    fun isBillingWindow(packageName: String, windowClass: String?): Boolean =
        packageName == PLAY_STORE_PACKAGE &&
            windowClass?.startsWith(BILLING_ACTIVITY_PREFIX) == true
}

/** Remembers qualified Play Store activity classes only within one accessibility window. */
class FrogBillingWindowTracker {
    private var playStoreWindowId: Int? = null
    private var qualifiedPlayStoreClass: String? = null

    /** Returns the effective class for this event, retaining generic events within a window. */
    fun observe(packageName: String, windowId: Int, windowClass: String?): String? {
        if (packageName != PLAY_STORE_PACKAGE) return windowClass

        if (windowId < 0) {
            playStoreWindowId = null
            qualifiedPlayStoreClass = null
            return windowClass
        }

        if (playStoreWindowId != windowId) {
            playStoreWindowId = windowId
            qualifiedPlayStoreClass = null
        }

        if (windowClass?.startsWith("com.") == true) {
            qualifiedPlayStoreClass = windowClass
        }
        return qualifiedPlayStoreClass ?: windowClass
    }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
