package com.focuslock.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserStats(
    val creditBalanceMinutes: Int = 0,
    val totalWorkMinutesToday: Int = 0,
    val totalDoomscrollMinutesToday: Int = 0,
    val tasksCompletedToday: Int = 0,
    val lastResetDate: String = ""
)
