package com.focuslock.app.data.model

/** OAuth credentials scoped to one TickTick account on this device. */
data class TickTickConnection(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val userName: String = "",
)

/** Local sync metadata and credentials for the currently bound FocusLock account. */
data class TickTickConnectionState(
    val accountId: String,
    val revision: Long,
    val generation: Long,
    val dirty: Boolean,
    val connection: TickTickConnection?,
)
