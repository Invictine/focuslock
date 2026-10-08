package com.focuslock.app.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clerk.api.Clerk
import com.clerk.ui.userbutton.UserButton
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler

@Composable
fun FocusAuthGate(
    state: FocusAuthState,
    onContinueOffline: () -> Unit,
    nativeSignInViewModel: NativeSignInViewModel? = null,
    onGoogle: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    when (state) {
        FocusAuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        FocusAuthState.SignedIn, FocusAuthState.Unconfigured -> content()
        FocusAuthState.SignedOut -> {
                val signInModel = nativeSignInViewModel ?: viewModel<NativeSignInViewModel>()
                val signInState by signInModel.uiState.collectAsState()
                BackHandler(enabled = signInState.step != NativeSignInStep.Options) { signInModel.backToOptions() }
                NativeSignInScreen(
                    state = signInState,
                    onGoogle = onGoogle,
                    onShowEmail = signInModel::showEmail,
                    onEmail = signInModel::submitEmail,
                    onPassword = signInModel::submitPassword,
                    onCode = signInModel::submitCode,
                    onProfile = signInModel::submitProfile,
                    onResendCode = signInModel::resendCode,
                    onChooseSecondFactor = signInModel::chooseSecondFactor,
                    onBackToOptions = signInModel::backToOptions,
                    onContinueOffline = { signInModel.backToOptions(); onContinueOffline() },
                    applySafeDrawingInsets = true,
                )
        }
    }
}

@Composable
fun AccountScreen(
    authState: FocusAuthState = FocusAuthState.SignedIn,
    syncStatus: String,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onGoogleSignIn: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
    googleSignInError: String? = null,
    googleSignInBusy: Boolean = false,
    googleSignInAvailable: Boolean = true,
) {
    val user by Clerk.userFlow.collectAsState(initial = null)
    val isSignedIn = authState == FocusAuthState.SignedIn && user != null
    var showSignOutConfirm by remember { mutableStateOf(false) }
    // MainActivity surfaces SyncStatus.Syncing as "Syncing…" — use it to lock the button.
    val isSyncing = syncStatus.startsWith("Syncing")
    // No internal verticalScroll/fillMaxSize: renders at content height so it can be
    // hosted inside a scrollable parent (MainActivity's AccountTab owns the scrolling).
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isSignedIn) {
            // Priority Account Active Hero Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        UserButton()
                        Column(modifier = Modifier.weight(1f)) {
                            val name = listOfNotNull(user?.firstName, user?.lastName)
                                .joinToString(" ")
                                .trim()
                                .ifBlank { null }
                            val email = user?.primaryEmailAddress?.emailAddress
                            Text(
                                text = name ?: email ?: "Priority Member",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (name != null && !email.isNullOrBlank()) {
                                Text(
                                    text = email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Verified,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Priority Active",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Cloud Status",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                syncStatus,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        FilledTonalButton(
                            onClick = onSyncNow,
                            enabled = !isSyncing,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSyncing) "Syncing…" else "Sync Now")
                        }
                    }
                }
            }

            // Sync explanation card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Multi-Device Protection Active",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Auto-sync runs every ~30 seconds and instantly after every focus event. Any Nuke lock on your phone simultaneously blocks your desktop companion and browser extensions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = { showSignOutConfirm = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign Out")
            }
        } else {
            // Keep the signed-out state focused on one decision: sign in to sync.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sync across your devices",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sign in to keep boundaries and progress in sync.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                    if (!googleSignInError.isNullOrBlank()) {
                        Text(googleSignInError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    GoogleSignInButton(
                        onClick = onGoogleSignIn,
                        enabled = googleSignInAvailable && !googleSignInBusy,
                        busy = googleSignInBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onOpenSignIn, enabled = !googleSignInBusy) { Text("Use email instead") }
                }
            }

        }
    }

    // Sign-out confirmation — destructive: stops sync until the user signs back in.
    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = {
                Text(
                    "Sign out of Priority Account?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    "Auto-sync and remote Nuke will stop on this device until you sign in again. " +
                        "Your local boundaries and credits stay on the device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOut()
                }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}
