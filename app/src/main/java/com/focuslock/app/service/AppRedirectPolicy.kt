package com.focuslock.app.service

/** A recent app-to-app transition observed while a Frog lock is active. */
data class AppRedirectCandidate(
    val sourcePackage: String,
    val targetPackage: String,
    val observedAtMs: Long,
)

/** Eligibility rules for returning from a blocked app to its allowed source. */
object AppRedirectPolicy {
    const val MAX_AGE_MS = 5_000L

    fun isEligible(
        candidate: AppRedirectCandidate,
        currentTarget: String?,
        nowMs: Long,
        sourceAllowed: Boolean,
        frogLocked: Boolean,
        nukeActive: Boolean,
        isWebsite: Boolean,
    ): Boolean {
        if (!frogLocked || nukeActive || isWebsite || !sourceAllowed) return false
        if (candidate.sourcePackage.isBlank() || candidate.targetPackage.isBlank()) return false
        if (candidate.sourcePackage == candidate.targetPackage || currentTarget != candidate.targetPackage) return false

        val ageMs = runCatching { Math.subtractExact(nowMs, candidate.observedAtMs) }.getOrNull()
            ?: return false
        return ageMs in 0L..MAX_AGE_MS
    }
}

/** Prevents repeatedly attempting the same source/target recovery pair. */
class AppRedirectAttemptLimiter {
    private data class PairKey(val sourcePackage: String, val targetPackage: String)

    private val attemptedAt = LinkedHashMap<PairKey, Long>()

    @Synchronized
    fun tryAcquire(candidate: AppRedirectCandidate, nowMs: Long): Boolean {
        if (candidate.sourcePackage.isBlank() || candidate.targetPackage.isBlank() ||
            candidate.sourcePackage == candidate.targetPackage
        ) return false

        pruneExpired(nowMs)
        val key = PairKey(candidate.sourcePackage, candidate.targetPackage)
        val previousAttemptMs = attemptedAt[key]
        if (previousAttemptMs != null) {
            val elapsedMs = runCatching { Math.subtractExact(nowMs, previousAttemptMs) }.getOrNull()
                ?: return false
            if (elapsedMs < 0L || elapsedMs <= COOLDOWN_MS) return false
        }

        attemptedAt.remove(key)
        while (attemptedAt.size >= MAX_TRACKED_PAIRS) {
            val oldest = attemptedAt.entries.firstOrNull()?.key ?: break
            attemptedAt.remove(oldest)
        }
        attemptedAt[key] = nowMs
        return true
    }

    private fun pruneExpired(nowMs: Long) {
        val expired = attemptedAt.filterValues { attemptedMs ->
            val elapsedMs = runCatching { Math.subtractExact(nowMs, attemptedMs) }.getOrNull()
            elapsedMs != null && elapsedMs > COOLDOWN_MS
        }.keys
        expired.forEach(attemptedAt::remove)
    }

    private companion object {
        const val COOLDOWN_MS = 15_000L
        const val MAX_TRACKED_PAIRS = 32
    }
}
