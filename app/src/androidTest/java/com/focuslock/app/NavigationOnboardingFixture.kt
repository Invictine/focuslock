package com.focuslock.app

import android.content.Context

/** Keeps navigation tests independent of first-run onboarding state. */
internal class NavigationOnboardingFixture(context: Context) {
    private val product = context.getSharedPreferences("focuslock_product_onboarding", Context.MODE_PRIVATE)
    private val permissions = context.getSharedPreferences("focuslock_onboarding", Context.MODE_PRIVATE)
    private val hadPaused = product.contains("paused_v2")
    private val paused = product.getBoolean("paused_v2", false)
    private val hadSkipped = permissions.contains("permissions_skipped")
    private val skipped = permissions.getBoolean("permissions_skipped", false)

    fun prepare() {
        product.edit().putBoolean("paused_v2", true).commit()
        permissions.edit().putBoolean("permissions_skipped", true).commit()
    }

    fun restore() {
        product.edit().apply {
            if (hadPaused) putBoolean("paused_v2", paused) else remove("paused_v2")
        }.commit()
        permissions.edit().apply {
            if (hadSkipped) putBoolean("permissions_skipped", skipped) else remove("permissions_skipped")
        }.commit()
    }
}
