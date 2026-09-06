package com.focuslock.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorkRecordSource {
    TICKTICK_NOTIFICATION,
    TICKTICK_API,
    TICKTICK_APP_FOCUS,
    MANUAL_ENTRY
}

@Serializable
data class TickTickWorkRecord(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val source: WorkRecordSource = WorkRecordSource.TICKTICK_NOTIFICATION,
    val earnedMinutesCredited: Int = 0,
    val projectName: String? = null
)
