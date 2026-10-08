package com.focuslock.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import com.focuslock.app.FocusLockApplication
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.onSuccess
import com.clerk.api.session.GetTokenOptions
import com.clerk.api.session.Session.SessionStatus
import com.clerk.api.session.pendingTaskKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val _accountId = MutableStateFlow<String?>(null)
    val accountId = _accountId.asStateFlow()

    private val configured: Boolean = try {
        com.focuslock.app.BuildConfig.CLERK_PUBLISHABLE_KEY.trim().startsWith("pk_")
    } catch (_: Exception) { false }

    init {
        if (!configured) {
            _state.value = FocusAuthState.Unconfigured
        } else {
            viewModelScope.launch {
                combine(Clerk.isInitialized, Clerk.userFlow, Clerk.sessionFlow) { initialized, user, session ->
                    _accountId.value = user?.id
                    when {
                        !initialized -> FocusAuthState.Loading
                        user != null && session?.status == SessionStatus.ACTIVE && session.pendingTaskKey == null -> FocusAuthState.SignedIn
                        else -> FocusAuthState.SignedOut
                    }
                }.collect { _state.value = it }
            }
        }
    }

    fun isConfigured(): Boolean = configured

    /** Stable Clerk subject used to scope the on-device sync cache. */
    suspend fun getAccountId(): String? {
        if (!configured) return null
        return try { Clerk.userFlow.first()?.id?.trim()?.ifBlank { null } } catch (_: Exception) { null }
    }

    /**
     * JWT for Convex (requires a "convex" JWT template in the Clerk dashboard).
     * Returns null when offline/unsigned — caller must skip sync.
     */
    suspend fun getConvexToken(): String? {
        if (!configured) return null
        return try {
            var token: String? = null
            // Convex must receive the Clerk JWT template audience. Falling back to the
            // default session JWT produces an opaque 401 and makes sync look flaky.
            Clerk.auth.getToken(GetTokenOptions(template = "convex")).onSuccess { token = it }
            token?.ifBlank { null }
        } catch (_: Exception) { null }
    }

    fun signOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try { Clerk.auth.signOut() } catch (_: Exception) { }
            // Let Google's provider offer all accounts again after an explicit sign-out.
            try {
                CredentialManager.create(FocusLockApplication.instance)
                    .clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) { }
            onDone()
        }
    }
}
