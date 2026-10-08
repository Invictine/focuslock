package com.focuslock.app.updates

/** Prevents duplicate checks when sign-in, rotation, or the installer resumes the app. */
internal class UpdateCheckGate(private val minimumIntervalMs: Long = 60_000L) {
    private var inFlight = false
    private var lastCheckMs: Long? = null

    fun tryStart(nowMs: Long, manual: Boolean = false): Boolean {
        if (inFlight) return false
        if (!manual && lastCheckMs?.let { nowMs - it < minimumIntervalMs } == true) return false
        inFlight = true
        lastCheckMs = nowMs
        return true
    }

    fun finish() {
        inFlight = false
    }
}
