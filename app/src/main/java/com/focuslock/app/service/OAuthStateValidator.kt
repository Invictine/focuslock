package com.focuslock.app.service

object OAuthStateValidator {
    fun isValid(expected: String?, received: String?, startedAt: Long, now: Long): Boolean =
        !expected.isNullOrBlank() && !received.isNullOrBlank() &&
            java.security.MessageDigest.isEqual(expected.toByteArray(), received.toByteArray()) &&
            startedAt > 0 && now - startedAt in 0..600_000L
}
