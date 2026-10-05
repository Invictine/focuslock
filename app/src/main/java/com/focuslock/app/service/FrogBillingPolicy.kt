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
