package com.focuslock.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clerk.api.Clerk
import com.clerk.api.auth.HostedAuthMode
import com.clerk.api.network.serialization.onFailure
import com.clerk.ui.auth.AuthView
import com.clerk.ui.userbutton.UserButton
import kotlinx.coroutines.launch

@Composable
fun FocusAuthGate(
    state: FocusAuthState,
    onContinueOffline: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (state) {
        FocusAuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        FocusAuthState.SignedIn, FocusAuthState.Unconfigured -> content()
        FocusAuthState.SignedOut -> SignInScreen(onContinueOffline = onContinueOffline)
    }
}

@Composable
fun SignInScreen(onContinueOffline: () -> Unit) {
    val scope = rememberCoroutineScope()
    // NOTE: no verticalScroll here — Clerk's AuthView fills max height and would
    // measure infinite inside a scrollable column (crash: Size out of range).
    // weight(1f) gives it bounded constraints instead.
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Welcome to FocusLock",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in to sync focus credits, block lists and work history with the desktop companion.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        // Prebuilt Clerk sign-in / sign-up (email, password, verification, OAuth per dashboard).
        Box(Modifier.weight(1f, fill = true).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            AuthView(modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = {
            scope.launch {
                Clerk.auth.startHostedAuth(mode = HostedAuthMode.SIGN_IN).onFailure { }
            }
        }) { Text("Use browser sign-in instead") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onContinueOffline) { Text("Continue offline") }
    }
}

@Composable
fun AccountScreen(
    syncStatus: String,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        UserButton()
        Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        Text(
            "Auto-sync runs every ~30s while signed in and after every focus event. Same Clerk account on desktop = same data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
