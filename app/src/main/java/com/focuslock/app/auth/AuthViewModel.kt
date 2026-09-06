package com.focuslock.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.onSuccess
import com.clerk.api.session.GetTokenOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface FocusAuthState {
    data object Loading : FocusAuthState
    data object SignedOut : FocusAuthState
    data object SignedIn : FocusAuthState
    /** Clerk key missing — app runs offline, sync disabled. */
    data object Unconfigured : FocusAuthState
}

/**
 * Observes Clerk SDK init + session. When no publishable key is configured
 * (BuildConfig empty) the app stays usable offline and sync is skipped.
 */
class AuthViewModel : ViewModel() {
    private val _state = MutableStateFlow<FocusAuthState>(FocusAuthState.Loading)
    val state = _state.asStateFlow()

    private val configured: Boolean = try {
        com.focuslock.app.BuildConfig.CLERK_PUBLISHABLE_KEY.trim().startsWith("pk_")
    } catch (_: Exception) { false }

    init {
        if (!configured) {
            _state.value = FocusAuthState.Unconfigured
        } else {
            viewModelScope.launch {
                combine(Clerk.isInitialized, Clerk.userFlow) { initialized, user ->
                    when {
                        !initialized -> FocusAuthState.Loading
                        user != null -> FocusAuthState.SignedIn
                        else -> FocusAuthState.SignedOut
                    }
                }.collect { _state.value = it }
            }
        }
    }

    fun isConfigured(): Boolean = configured

    /**
     * JWT for Convex (requires a "convex" JWT template in the Clerk dashboard).
     * Returns null when offline/unsigned — caller must skip sync.
     */
    suspend fun getConvexToken(): String? {
        if (!configured) return null
        return try {
            var token: String? = null
            try {
                Clerk.auth.getToken(GetTokenOptions(template = "convex")).onSuccess { token = it }
            } catch (_: Exception) {
                Clerk.auth.getToken().onSuccess { token = it }
            }
            token?.ifBlank { null }
        } catch (_: Exception) { null }
    }

    fun signOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try { Clerk.auth.signOut() } catch (_: Exception) { }
            onDone()
        }
    }
}
