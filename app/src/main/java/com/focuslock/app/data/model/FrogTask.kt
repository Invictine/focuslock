package com.focuslock.app.data.model

import kotlinx.serialization.Serializable

/**
 * One pickable "eat the frog" task: either an open TickTick task (synced through the
 * open-task cache) or an on-device manual entry.
 *
 * [id] is the stable identity used for selection/dedupe: the raw TickTick task id, or
 * "manual_<epochMillis>" for manual entries. [dueDate] keeps the raw TickTick
 * dueDate string (may be empty) — it is display-only and never used for counting.
 */
@Serializable
data class FrogTask(
    val id: String,            // TickTick task id, or "manual_<epochMillis>"
    val title: String,
    val projectId: String = "",
    val projectName: String = "",
    val dueDate: String = "",  // raw TickTick dueDate, may be empty
    val source: String = SOURCE_TICKTICK,
) {
    companion object {
        const val SOURCE_TICKTICK = "ticktick"
        const val SOURCE_MANUAL = "manual"
    }
}

/**
 * Lifecycle of the day's frog:
 *  - [NOT_ARMED]: the feature is off, or today's wake trigger has not fired yet
 *  - [PICK_FROG]: armed but no task selected yet
 *  - [WORKING]: armed with a selected task, not completed yet
 *  - [COMPLETE]: ticked off with at least the required focus time tracked
 */
@Serializable
enum class FrogPhase {
    NOT_ARMED,
    PICK_FROG,
    WORKING,
    COMPLETE;

    companion object {
        /**
         * Derives the phase. [selected] is true when a frog task is currently selected.
         * Pure and dependency-free so it can be unit-tested on the JVM.
         */
        fun from(
            armed: Boolean,
            selected: Boolean,
            tickedOff: Boolean,
            trackedSeconds: Int,
            requiredSeconds: Int,
        ): FrogPhase = when {
            !armed -> NOT_ARMED
            !selected -> PICK_FROG
            tickedOff && trackedSeconds >= requiredSeconds -> COMPLETE
            else -> WORKING
        }
    }
}

/**
 * Snapshot of the "eat the frog" state for one cycle day, combining the persisted
 * DataStore values with the derived [phase]/[locked] flags. [openTasks] is the last
 * cached picker list (kept across daily rollovers until refreshed).
 */
data class FrogState(
    val cycleDate: String,
    val enabled: Boolean,
    val armed: Boolean,
    val phase: FrogPhase,
    val frog: FrogTask?,
    val tickedOff: Boolean,
    val trackedSeconds: Int,
    val requiredSeconds: Int,
    val locked: Boolean,
    val openTasks: List<FrogTask>,
)
