package com.focuslock.app.service

/** Access decision for an ordinary selected app; Strict Mode is not an access policy. */
internal object AppBlockPolicy {
    fun blockReason(blocked: Boolean, scheduleActive: Boolean, balanceSeconds: Long): String? = when {
        !blocked -> null
        scheduleActive -> "schedule"
        balanceSeconds <= 0L -> "manual"
        else -> null
    }
}
