package com.focuslock.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.res.painterResource
import com.focuslock.app.R
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen app-owned authentication UI. AuthView is intentionally not used here. */
@Composable
fun NativeSignInScreen(
    state: NativeSignInUiState,
    onGoogle: () -> Unit,
    onShowEmail: () -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCode: (String) -> Unit,
    onProfile: (Map<String, String>) -> Unit,
    onResendCode: () -> Unit,
    onChooseSecondFactor: (String) -> Unit,
    onBackToOptions: () -> Unit,
    onContinueOffline: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    googleError: String? = null,
    applySafeDrawingInsets: Boolean = false,
) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (applySafeDrawingInsets) Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing) else Modifier)
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("FocusLock", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(
                when (state.step) {
                    NativeSignInStep.Options -> "Stay focused everywhere"
                    NativeSignInStep.Email -> "Continue with email"
                    NativeSignInStep.Code -> when (state.verificationKind) {
                        "totp" -> "Verify your account"
                        "backup_code" -> "Use a backup code"
                        "phone_code" -> "Check your phone"
                        else -> "Check your email"
                    }
                    NativeSignInStep.Password -> if (state.passwordIsNew) "Set a new password" else "Enter your password"
                    NativeSignInStep.Profile -> "Finish setting up your account"
                    NativeSignInStep.Complete -> "You're all set"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                when (state.step) {
                    NativeSignInStep.Options, NativeSignInStep.Complete ->
                        "Sync your focus boundaries, credits, and progress across devices."
                    NativeSignInStep.Email -> "Enter the email address for your FocusLock account."
                    NativeSignInStep.Code -> state.codePrompt.ifBlank { "Enter the verification code we sent you." }
                    NativeSignInStep.Password -> if (state.passwordIsNew) "Choose a new password to secure your account." else "Enter your account password to continue."
                    NativeSignInStep.Profile -> "A few details are needed to finish creating your account."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (state.error != null || googleError != null) {
                Text(
                    state.error ?: googleError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            when (state.step) {
                NativeSignInStep.Options -> {
                    GoogleSignInButton(
                        onClick = onGoogle,
                        enabled = state.googleAvailable && !state.busy,
                        busy = state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onShowEmail, enabled = state.emailAvailable && !state.busy) {
                        Text("Use email instead")
                    }
                    onContinueOffline?.let { offline ->
                        TextButton(onClick = offline) { Text("Continue offline") }
                    }
                }
                NativeSignInStep.Email -> {
                    var email by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(email, { email = it }, label = { Text("Email") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Button(onClick = { onEmail(email.trim()) }, enabled = email.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        ActionLabel(state.busy, "Continue")
                    }
                }
                NativeSignInStep.Code -> {
                    var code by remember(state.verificationKind, state.codePrompt) { mutableStateOf("") }
                    OutlinedTextField(code, { code = it }, label = { Text("Verification code") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (state.verificationKind == "backup_code") KeyboardType.Ascii else KeyboardType.NumberPassword))
                    Button(onClick = { onCode(code.trim()) }, enabled = code.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp)) { ActionLabel(state.busy, "Verify") }
                    if (state.canResendCode) TextButton(onClick = onResendCode, enabled = !state.busy) { Text("Resend code") }
                    state.secondFactors.forEach { factor ->
                        TextButton(onClick = { onChooseSecondFactor(factor) }, enabled = !state.busy) {
                            Text("Use ${factorLabel(factor)} instead")
                        }
                    }
                }
                NativeSignInStep.Password -> {
                    var password by remember { mutableStateOf("") }
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Button(onClick = { onPassword(password) }, enabled = password.isNotEmpty() && !state.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp)) { ActionLabel(state.busy, "Continue") }
                }
                NativeSignInStep.Profile -> {
                    val fields = remember(state.requiredFields) { state.requiredFields.associateWith { "" } }
                    var values by remember(state.requiredFields) { mutableStateOf(fields) }
                    state.requiredFields.forEach { field ->
                        OutlinedTextField(values[field].orEmpty(),
                            { value -> values = values + (field to value) },
                            label = { Text(profileFieldLabel(field)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (field == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = when (field) {
                                "password" -> KeyboardType.Password
                                "email_address" -> KeyboardType.Email
                                "phone_number" -> KeyboardType.Phone
                                else -> KeyboardType.Text
                            }))
                    }
                    Button(onClick = { onProfile(values) }, enabled = !state.busy && values.values.all(String::isNotBlank),
                        modifier = Modifier.fillMaxWidth().height(52.dp)) { ActionLabel(state.busy, "Finish") }
                }
                NativeSignInStep.Complete -> Unit
            }

            if (state.step != NativeSignInStep.Options) {
                TextButton(onClick = onBackToOptions, enabled = !state.busy) { Text("Back to sign-in options") }
            } else if (onBack != null) {
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

@Composable
private fun ActionLabel(busy: Boolean, label: String) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("Please wait…")
    }
    else Text(label, fontWeight = FontWeight.SemiBold)
}

/** Official Google brand asset, without recoloring or redrawing it. */
@Composable
internal fun GoogleMark() {
    Image(painterResource(R.drawable.ic_google_sign_in), contentDescription = null, modifier = Modifier.size(20.dp))
}

@Composable
internal fun GoogleSignInButton(onClick: () -> Unit, busy: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else GoogleMark()
        Spacer(Modifier.width(12.dp))
        Text(if (busy) "Signing in…" else "Continue with Google", fontWeight = FontWeight.SemiBold)
    }
}

private fun factorLabel(factor: String): String = when (factor) {
    "totp" -> "an authenticator app"
    "backup_code" -> "a backup code"
    "phone_code" -> "a text message"
    "email_code" -> "an email code"
    "passkey" -> "a passkey"
    else -> "another verification method"
}

private fun profileFieldLabel(field: String): String = when (field) {
    "first_name" -> "First name"
    "last_name" -> "Last name"
    "email_address" -> "Email address"
    "phone_number" -> "Phone number"
    "password" -> "Create password"
    "username" -> "Username"
    else -> field.replace('_', ' ').replaceFirstChar(Char::uppercase)
}
