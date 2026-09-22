package com.focuslock.app.ui.strict

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focuslock.app.BuildConfig
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.sync.ConvexSyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ApprovalUnlockCard(settings: SettingsRepository) {
    val auth: AuthViewModel = viewModel()
    val authState by auth.state.collectAsStateWithLifecycle()
    val active by settings.lockdownModeFlow.collectAsStateWithLifecycle(false)
    val endsAt by settings.lockdownEndsAtFlow.collectAsStateWithLifecycle(0L)
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var savedEmail by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val signedIn = authState is FocusAuthState.SignedIn

    suspend fun client(): ConvexSyncClient {
        val token = auth.getConvexToken() ?: error("Sign in and connect to the internet to continue.")
        return ConvexSyncClient(BuildConfig.CONVEX_URL, tokenProvider = { token })
    }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Could not connect. Try again." }
            finally { busy = false }
        }
    }
    LaunchedEffect(authState) {
        email = ""
        savedEmail = ""
        notice = null
        if (signedIn) {
            try {
                val result = client().getStrictGuardian() ?: error("Approval service unavailable")
                savedEmail = result.optString("email", "")
                email = savedEmail
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Connect to load your approval contact." }
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Email-approved unlock", style = MaterialTheme.typography.titleMedium)
            Text("Choose a trusted person before starting a commitment. Only their one-time approval can end it early. Permanent blocks stay active.", style = MaterialTheme.typography.bodyMedium)
            if (!signedIn) {
                Text("Sign in from Settings to set up email approval.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Trusted person's email") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !active && !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (!active) {
                    Button(enabled = !busy && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() && email.trim() != savedEmail,
                        onClick = { perform {
                            val connection = client()
                            check(connection.configureStrictGuardian(email.trim())) { "Could not save the contact. Check your connection and sync status." }
                            savedEmail = email.trim()
                            notice = "Approval contact saved. You can now start a commitment."
                        } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Save approval contact") }
                } else {
                    Text(if (savedEmail.isBlank()) "No contact configured. Set one up after this commitment ends." else "Your approval contact is fixed until this commitment ends.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && savedEmail.isNotBlank(), onClick = { perform {
                        FocusLockApplication.instance.syncManager.syncNow(auth)
                        val connection = client()
                        val state = connection.getStrictApprovalState() ?: error("Could not load your commitment. Sync and try again.")
                        val session = state.optString("strictSessionId", "")
                        check(session.isNotBlank() && state.optLong("strictEndsAt") == endsAt) { "This commitment has not synced yet. Try again when online." }
                        val result = connection.requestStrictApproval(session, endsAt)
                            ?: error("Could not request approval. Check your connection; requests are limited to three per hour.")
                        notice = if (result.optBoolean("sent")) "Approval email sent. The link expires in 30 minutes."
                            else result.optString("reason", "Email could not be sent. Try again later.")
                    } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connecting…" else "Request approval by email") }
                    TextButton(enabled = !busy, onClick = { perform {
                        FocusLockApplication.instance.syncManager.syncNow(auth)
                        notice = if (settings.isLockdownModeEnabled()) "No approval received yet. Your commitment remains active." else "Commitment ended. Permanent blocks remain active."
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Check approval") }
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
