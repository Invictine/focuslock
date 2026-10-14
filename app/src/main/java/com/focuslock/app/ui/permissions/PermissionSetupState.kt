package com.focuslock.app.ui.permissions

import android.content.Context

/** Device-local progress for the shared permission setup wizard. */
internal data class PermissionSetupState(
    val steps: List<PermissionKind> = emptyList(),
    val currentIndex: Int = 0,
    val active: Boolean = false,
    val visible: Boolean = false,
    val skipped: Boolean = false,
) {
    val currentStep: PermissionKind?
        get() = steps.getOrNull(currentIndex)

    fun begin(missing: List<PermissionKind>): PermissionSetupState {
        if (missing.isEmpty()) return copy(active = false, visible = false)
        val savedSteps = if (active && steps.isNotEmpty()) steps else missing
        val nextIndex = if (active) currentIndex.coerceIn(0, (savedSteps.size - 1).coerceAtLeast(0)) else 0
        return copy(
            steps = savedSteps,
            currentIndex = nextIndex,
            active = true,
            visible = true,
            skipped = false,
        )
    }

    fun close(): PermissionSetupState = copy(visible = false)

    fun advance(): PermissionSetupState = if (currentIndex + 1 >= steps.size) {
        copy(active = false, visible = false, currentIndex = steps.size)
    } else {
        copy(currentIndex = currentIndex + 1)
    }

    fun skipAll(): PermissionSetupState = copy(active = false, visible = false, skipped = true)

    fun encodeSteps(): String = steps.joinToString(",") { it.name }

    companion object {
        fun fromStored(
            encodedSteps: String?,
            currentIndex: Int,
            active: Boolean,
            visible: Boolean,
            skipped: Boolean,
        ): PermissionSetupState {
            val steps = encodedSteps.orEmpty()
                .split(',')
                .mapNotNull { raw -> PermissionKind.entries.firstOrNull { it.name == raw } }
            return PermissionSetupState(
                steps = steps,
                currentIndex = currentIndex.coerceIn(0, steps.size),
                active = active && steps.isNotEmpty(),
                visible = visible && steps.isNotEmpty(),
                skipped = skipped,
            )
        }

        fun firstRun(missing: List<PermissionKind>, previouslySkipped: Boolean): PermissionSetupState =
            if (previouslySkipped || missing.isEmpty()) PermissionSetupState(skipped = previouslySkipped)
            else PermissionSetupState().begin(missing)
    }
}

/** SharedPreferences adapter kept small so flow transitions remain unit-testable. */
internal class PermissionSetupStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PermissionSetupState = PermissionSetupState.fromStored(
        encodedSteps = prefs.getString(KEY_STEPS, null),
        currentIndex = prefs.getInt(KEY_INDEX, 0),
        active = prefs.getBoolean(KEY_ACTIVE, false),
        visible = prefs.getBoolean(KEY_VISIBLE, false),
        skipped = prefs.getBoolean(KEY_SKIPPED, false),
    )

    fun save(state: PermissionSetupState) {
        prefs.edit()
            .putString(KEY_STEPS, state.encodeSteps())
            .putInt(KEY_INDEX, state.currentIndex)
            .putBoolean(KEY_ACTIVE, state.active)
            .putBoolean(KEY_VISIBLE, state.visible)
            .putBoolean(KEY_SKIPPED, state.skipped)
            .putBoolean(LEGACY_SKIPPED, state.skipped)
            .apply()
    }

    fun wasPreviouslySkipped(): Boolean =
        prefs.getBoolean(KEY_SKIPPED, prefs.getBoolean(LEGACY_SKIPPED, false))

    private companion object {
        const val PREFS = "focuslock_onboarding"
        const val KEY_STEPS = "permission_setup_steps"
        const val KEY_INDEX = "permission_setup_index"
        const val KEY_ACTIVE = "permission_setup_active"
        const val KEY_VISIBLE = "permission_setup_visible"
        const val KEY_SKIPPED = "permission_setup_skipped"
        const val LEGACY_SKIPPED = "permissions_skipped"
    }
}
