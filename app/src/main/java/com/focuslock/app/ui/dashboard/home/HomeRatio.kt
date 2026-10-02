package com.focuslock.app.ui.dashboard.home

import java.util.Locale
import kotlin.math.ceil

data class HomeRatioSummary(
    val title: String,
    val detail: String,
    val ratioLabel: String,
    val behind: Boolean = false,
)

fun formatHomeDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    if (safe in 1..59) return "<1m"
    val minutes = safe / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** Today's measured focus/leisure, independent of bank carryover or task rewards. */
fun evaluateHomeRatio(
    focusMinutes: Int,
    leisureSeconds: Long?,
    focusLoaded: Boolean,
    target: Double,
): HomeRatioSummary {
    val safeTarget = target.takeIf { it.isFinite() && it > 0 } ?: 2.0
    val goal = String.format(Locale.getDefault(), "%.1f:1", safeTarget)
    if (!focusLoaded || leisureSeconds == null) return HomeRatioSummary(
        "Ratio unavailable", "Waiting for today's focus and leisure data · Goal $goal", "—",
    )
    val focus = focusMinutes.coerceAtLeast(0).toLong() * 60
    val leisure = leisureSeconds.coerceAtLeast(0)
    if (focus == 0L && leisure == 0L) return HomeRatioSummary(
        "A fresh start", "Start a focus session · Goal $goal focus to leisure", "—",
    )
    if (leisure == 0L) return HomeRatioSummary(
        "Focus is ahead", "No leisure tracked today · Goal $goal", "No leisure yet",
    )
    val ratio = focus.toDouble() / leisure
    val label = String.format(Locale.getDefault(), "%.1f:1", ratio)
    val missing = ceil((leisure * safeTarget - focus) / 60.0).toLong().coerceAtLeast(0)
    return if (ratio >= safeTarget) HomeRatioSummary(
        "On track", "You're meeting your $goal focus-to-leisure goal", label,
    ) else HomeRatioSummary(
        "Leisure is ahead", "${formatHomeDuration(missing * 60)} more focus to reach $goal", label, true,
    )
}
