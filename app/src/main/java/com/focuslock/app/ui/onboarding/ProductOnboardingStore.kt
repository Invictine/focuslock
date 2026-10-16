package com.focuslock.app.ui.onboarding

import android.content.Context

/** Completion is separate from pausing: opening setup never completes the tour. */
internal class ProductOnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("focuslock_product_onboarding", Context.MODE_PRIVATE)

    val isComplete: Boolean get() = prefs.getBoolean("complete_v2", false)
    val shouldAutoShow: Boolean get() = !isComplete && !prefs.getBoolean("paused_v2", false)
    val shouldResumeLiveTutorial: Boolean get() = prefs.getBoolean("live_tutorial_v2", false) && !isComplete
    val hasActiveLiveTutorial: Boolean get() = shouldResumeLiveTutorial && !prefs.getBoolean("paused_v2", false)
    val pageIndex: Int get() = prefs.getInt("page_v2", 0).coerceIn(productTourPages.indices)

    fun taskIndex(page: String): Int = prefs.getInt("task_$page", 0)

    fun saveTask(page: String, index: Int) {
        prefs.edit().putInt("task_$page", index.coerceAtLeast(0)).apply()
    }

    fun savePage(index: Int) {
        prefs.edit().putInt("page_v2", index.coerceIn(productTourPages.indices)).apply()
    }

    fun pause() {
        prefs.edit().putBoolean("paused_v2", true).apply()
    }

    fun finish() {
        prefs.edit().putBoolean("complete_v2", true).putBoolean("paused_v2", false)
            .putBoolean("live_tutorial_v2", false).apply()
    }

    fun replay() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("task_") }.forEach { remove(it) } }.apply()
        prefs.edit().putBoolean("complete_v2", false).putBoolean("paused_v2", false)
            .putBoolean("live_tutorial_v2", false).putInt("page_v2", 0).apply()
    }

    fun resume() {
        prefs.edit().putBoolean("paused_v2", false).apply()
    }

    fun beginLiveTutorial() {
        prefs.edit().putBoolean("paused_v2", false).putBoolean("live_tutorial_v2", true).apply()
    }

    fun endLiveTutorial() {
        prefs.edit().putBoolean("live_tutorial_v2", false).apply()
    }
}
