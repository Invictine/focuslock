package com.focuslock.app.ui.blocker

import com.focuslock.app.data.model.FrogState

/** Builds a truthful explanation of what must happen before a blocked app can open. */
internal fun blockUnlockSummary(
    blockReason: String?,
    frog: FrogState?,
    balanceSeconds: Long?
): String? {
    if (blockReason == "permanent") return "Focus time cannot unlock a permanent block."

    when (blockReason) {
        "limit" -> return "Your daily limit resets tomorrow."
        "schedule" -> return "Your block schedule must end first."
        "nuke" -> return "The nuclear block must end first."
    }

    if (frog?.locked == true || blockReason == "frog") {
        return frog?.let(::frogRequirement)
    }

    return when {
        balanceSeconds == null -> null
        balanceSeconds > 0L -> "Leisure time is available."
        else -> "Log 1 min more focus to earn leisure time."
    }
}

private fun frogRequirement(state: FrogState): String {
    if (state.frog == null) {
        return "Choose your frog, then focus for ${ceilMinutes(state.requiredSeconds.toLong())} min and tick it off."
    }

    val remainingSeconds = (state.requiredSeconds.toLong() - state.trackedSeconds.toLong()).coerceAtLeast(0L)
    val remainingMinutes = ceilMinutes(remainingSeconds)
    return when {
        remainingMinutes > 0 -> "${remainingMinutes} min more focus on your frog.${if (state.tickedOff) "" else " Tick it off when complete."}"
        !state.tickedOff -> "Focus target met. Tick off your frog to unlock."
        else -> "Frog complete."
    }
}

private fun ceilMinutes(seconds: Long): Long = (seconds.coerceAtLeast(0L) + 59L) / 60L
