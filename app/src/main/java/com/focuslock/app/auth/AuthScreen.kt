package com.focuslock.app.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clerk.api.Clerk
import com.clerk.api.auth.HostedAuthMode
import com.clerk.api.network.serialization.onFailure
import com.clerk.api.network.serialization.onSuccess
import com.clerk.ui.auth.AuthView
import com.clerk.ui.userbutton.UserButton
import com.focuslock.app.ui.components.IconBadge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FocusAuthGate(
    state: FocusAuthState,
    onContinueOffline: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (state) {
        FocusAuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        FocusAuthState.SignedIn, FocusAuthState.Unconfigured -> content()
        FocusAuthState.SignedOut -> SignInScreen(onContinueOffline = onContinueOffline)
    }
}

@Composable
fun SignInScreen(onContinueOffline: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showInAppAuth by remember { mutableStateOf(false) }
    var signInLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    // Single one-shot entrance flag; every element stages itself off this via StaggeredEnter.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // Memoize the backdrop brush so a new gradient isn't allocated on every recomposition.
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val backgroundColor = MaterialTheme.colorScheme.background
    val backdropBrush = remember(primaryContainer, secondaryContainer, backgroundColor) {
        Brush.verticalGradient(
            colors = listOf(
                primaryContainer.copy(alpha = 0.42f),
                backgroundColor,
                secondaryContainer.copy(alpha = 0.30f),
            )
        )
    }

    // No Scaffold hosts this screen, so the root owns safeDrawing (status bar, display cutout,
    // nav bar and IME). The background is painted full-bleed before the inset padding so the
    // gradient runs under the system bars while content never does.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .background(backdropBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        SignInBackdrop(Modifier.matchParentSize())

        if (showInAppAuth) {
            // Auth form mode: bounded full-height column so Clerk's AuthView keeps the
            // weight-based height it had before; the root still owns safe-drawing insets.
            // Vertical breathing room stays minimal so content starts just under the bar.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StaggeredEnter(visible = entered, index = 0) { HeroEmblem(compact = true) }
                Spacer(Modifier.height(12.dp))
                SignInHeadline(visible = entered)
                Spacer(Modifier.height(20.dp))

                authError?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Box(Modifier.weight(1f, fill = true).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    AuthView(modifier = Modifier.fillMaxWidth())
                }

                TextButton(onClick = {
                    showInAppAuth = false
                    authError = null
                }) {
                    Text("Back to sign-in options", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // Centering + scroll safety: a wrap-height Column aligned to Center scrolls once it
            // exceeds the viewport (short screens) and stays optically centered when it doesn't.
            // Vertical breathing room stays minimal; safe-drawing insets are owned by the root.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StaggeredEnter(visible = entered, index = 0) { HeroEmblem() }
                Spacer(Modifier.height(16.dp))
                SignInHeadline(visible = entered)
                Spacer(Modifier.height(20.dp))

                // Feature Highlights
                StaggeredEnter(visible = entered, index = 3, modifier = Modifier.fillMaxWidth()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            PriorityFeatureRow(
                                icon = Icons.Rounded.Lock,
                                title = "Multi-Device Nuke Lock",
                                description = "Desktop companion & phone lock together during focus."
                            )
                            PriorityFeatureRow(
                                icon = Icons.Rounded.CloudSync,
                                title = "Real-Time Cloud Sync",
                                description = "Focus credits & daily habits sync seamlessly via Convex cloud."
                            )
                            PriorityFeatureRow(
                                icon = Icons.Rounded.Laptop,
                                title = "Desktop Companion",
                                description = "Enforce boundaries across macOS, Windows, and browsers."
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                StaggeredEnter(visible = entered, index = 4, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(
                            onClick = {
                                authError = null
                                signInLoading = true
                                scope.launch {
                                    Clerk.auth.startHostedAuth(mode = HostedAuthMode.SIGN_IN)
                                        .onSuccess { signInLoading = false }
                                        .onFailure {
                                            signInLoading = false
                                            authError = "Couldn't open secure sign-in. Use the email form below."
                                            showInAppAuth = true
                                        }
                                }
                            },
                            enabled = !signInLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            if (signInLoading) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (signInLoading) "Opening sign-in…" else "Sign In to Priority Account",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Tonal secondary action — no outlined/bordered container.
                        FilledTonalButton(
                            onClick = { showInAppAuth = true },
                            enabled = !signInLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(Icons.Rounded.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign in with email form")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                SignInFooter(visible = entered, onContinueOffline = onContinueOffline)
            }
        }
    }
}

/**
 * Small staged-entrance wrapper. Each element waits ~60ms after the previous one and then
 * fades/slides in with interruptible-safe springs (finite specs, no blocking loops).
 */
@Composable
private fun StaggeredEnter(
    visible: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            if (index > 0) delay(index * 60L)
            revealed = true
        }
    }

    val enter: EnterTransition = if (compact) {
        // Smaller elements settle faster so the tail of the cascade feels snappy.
        slideInVertically(
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
            initialOffsetY = { it / 6 }
        ) + fadeIn(animationSpec = spring(dampingRatio = 1f, stiffness = 1800f))
    } else {
        slideInVertically(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
            initialOffsetY = { it / 4 }
        ) + fadeIn(animationSpec = spring(dampingRatio = 1f, stiffness = 1600f))
    }

    AnimatedVisibility(
        visible = revealed,
        modifier = modifier,
        enter = enter,
    ) {
        content()
    }
}

@Composable
private fun SignInHeadline(visible: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StaggeredEnter(visible = visible, index = 1) {
            Text(
                "FocusLock Priority Account",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        StaggeredEnter(visible = visible, index = 2, compact = true) {
            Text(
                "Activate cross-device discipline. Locking your phone instantly locks your computer companion.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SignInFooter(visible: Boolean, onContinueOffline: () -> Unit) {
    StaggeredEnter(visible = visible, index = 5, compact = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextButton(onClick = onContinueOffline) {
                Text("Continue in Offline Mode (Local Only)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Offline mode keeps boundaries, credits, and stats on this device only. You can sign in later from Account.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/**
 * Layered hero: concentric tonal rings (filled, never stroked), an infinite halo pulse read in
 * the draw/layer phase, and the gradient shield disc on top.
 */
@Composable
private fun HeroEmblem(compact: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    // Memoize the brush so a new gradient isn't allocated on every recomposition.
    val gradient = remember(primary, tertiary) {
        Brush.linearGradient(colors = listOf(primary, tertiary))
    }

    val halo = rememberInfiniteTransition(label = "signin-halo")
    val haloAlpha by halo.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "signin-halo-alpha"
    )
    val haloScale by halo.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "signin-halo-scale"
    )

    val emblemSize = if (compact) 104.dp else 160.dp
    val haloSize = if (compact) 64.dp else 96.dp
    val discSize = if (compact) 56.dp else 80.dp
    val iconSize = if (compact) 28.dp else 40.dp

    Box(modifier = Modifier.size(emblemSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ringCenter = center
            drawCircle(
                color = primaryContainer.copy(alpha = 0.10f),
                radius = size.minDimension * 0.50f,
                center = ringCenter
            )
            drawCircle(
                color = primaryContainer.copy(alpha = 0.20f),
                radius = size.minDimension * 0.375f,
                center = ringCenter
            )
            drawCircle(
                color = primaryContainer.copy(alpha = 0.32f),
                radius = size.minDimension * 0.26f,
                center = ringCenter
            )
        }

        // Infinite halo pulse, read in the layer/draw phase so it never recomposes the emblem.
        Box(
            modifier = Modifier
                .size(haloSize)
                .graphicsLayer {
                    scaleX = haloScale
                    scaleY = haloScale
                }
                .drawBehind {
                    drawCircle(color = primary, alpha = haloAlpha)
                }
        )

        Box(
            modifier = Modifier
                .size(discSize)
                .background(brush = gradient, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = onPrimary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Full-bleed decorative layer: two soft tonal blobs plus faint orbit rings behind the hero.
 * Purely tonal fills (no hardcoded colors, no borders), sourced from the color scheme.
 */
@Composable
private fun SignInBackdrop(modifier: Modifier = Modifier) {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val topBlobRadius = width * 0.62f
        val topBlobCenter = Offset(width * 0.88f, height * 0.08f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryContainer.copy(alpha = 0.55f), background.copy(alpha = 0f)),
                center = topBlobCenter,
                radius = topBlobRadius
            ),
            radius = topBlobRadius,
            center = topBlobCenter
        )

        val bottomBlobRadius = width * 0.72f
        val bottomBlobCenter = Offset(width * 0.06f, height * 0.94f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryContainer.copy(alpha = 0.45f), background.copy(alpha = 0f)),
                center = bottomBlobCenter,
                radius = bottomBlobRadius
            ),
            radius = bottomBlobRadius,
            center = bottomBlobCenter
        )

        val orbitCenter = Offset(width / 2f, height * 0.28f)
        drawCircle(color = primary.copy(alpha = 0.05f), radius = width * 0.42f, center = orbitCenter)
        drawCircle(color = primary.copy(alpha = 0.04f), radius = width * 0.56f, center = orbitCenter)
    }
}

@Composable
fun AccountScreen(
    authState: FocusAuthState = FocusAuthState.SignedIn,
    syncStatus: String,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val user by Clerk.userFlow.collectAsState(initial = null)
    val isSignedIn = authState == FocusAuthState.SignedIn && user != null
    var showSignOutConfirm by remember { mutableStateOf(false) }
    // MainActivity surfaces SyncStatus.Syncing as "Syncing…" — use it to lock the button.
    val isSyncing = syncStatus.startsWith("Syncing")
    // Memoize the brush so a new gradient isn't allocated on every recomposition.
    val accountPrimary = MaterialTheme.colorScheme.primary
    val accountTertiary = MaterialTheme.colorScheme.tertiary
    val accountGradient = remember(accountPrimary, accountTertiary) {
        Brush.linearGradient(colors = listOf(accountPrimary, accountTertiary))
    }

    // No internal verticalScroll/fillMaxSize: renders at content height so it can be
    // hosted inside a scrollable parent (MainActivity's AccountTab owns the scrolling).
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Account",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

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
            // Signed-out Priority Account Card (The prominent "Priority Account" experience)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    brush = accountGradient,
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "FocusLock Priority Account",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Cross-device sync & lock enforcement",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                    PriorityFeatureRow(
                        icon = Icons.Rounded.Lock,
                        title = "Multi-Device Nuke Lock",
                        description = "Locking on mobile locks desktop companion & browser."
                    )
                    PriorityFeatureRow(
                        icon = Icons.Rounded.CloudSync,
                        title = "Real-Time Cloud Sync",
                        description = "Credits, task records, and boundaries sync across devices."
                    )
                    PriorityFeatureRow(
                        icon = Icons.Rounded.Computer,
                        title = "Desktop Companion",
                        description = "Connects with the macOS/Windows desktop companion app."
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                Clerk.auth.startHostedAuth(mode = HostedAuthMode.SIGN_IN).onFailure {
                                    onSignIn()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Rounded.Stars, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sign In to Priority Account",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // Current Mode Notice
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Currently running in Offline Mode. Focus credits, boundaries, and app locks are stored locally on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

@Composable
private fun PriorityFeatureRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBadge(
            icon = icon,
            size = 40.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
