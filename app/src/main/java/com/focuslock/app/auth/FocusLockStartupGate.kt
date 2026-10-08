package com.focuslock.app.auth

import androidx.compose.runtime.Composable

/** Resolve account startup before composing the app or its onboarding dialogs. */
@Composable
internal fun FocusLockStartupGate(
    authState: FocusAuthState,
    offlineMode: Boolean,
    onContinueOffline: () -> Unit,
    content: @Composable () -> Unit,
) {
    FocusAuthGate(
        state = if (offlineMode) FocusAuthState.Unconfigured else authState,
        onContinueOffline = onContinueOffline,
        content = content,
    )
}
