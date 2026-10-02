package com.focuslock.app.service

/** Pure precedence policy for deciding whether a detected website should be blocked now. */
internal object WebsiteBlockPolicy {
    fun blockReason(
        blocked: Boolean,
        permanent: Boolean,
        groupLimitExceeded: Boolean,
        strict: Boolean,
        scheduleActive: Boolean,
        suppressed: Boolean,
        balanceSeconds: Long,
    ): String? = when {
        permanent -> "permanent"
        groupLimitExceeded -> "limit"
        !blocked -> null
        strict -> "strict"
        scheduleActive -> "schedule"
        suppressed -> null
        balanceSeconds <= 0L -> "manual"
        else -> null
    }
}
