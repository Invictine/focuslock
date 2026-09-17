package com.focuslock.app.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslock.app.service.InstalledAppsRepository

/**
 * Shared spacing/size tokens for the app's screens. Screens should prefer these over
 * one-off dp literals so hierarchy (section gaps vs. item gaps) stays consistent.
 */
object UiTokens {
    val ScreenPadding = 16.dp
    val SectionGap = 24.dp
    val ItemGap = 12.dp
    val IconTileSize = 40.dp
    val BadgeSize = 44.dp
}

/**
 * Rounded-square ("squircle") tonal icon container used for static glyphs across the
 * app: 30%-of-size corner radius (never a circle, never a small fixed square), tonal
 * fill from the passed colors, and a centered filled/rounded glyph at ~50% of the tile.
 * All colors must come from [MaterialTheme.colorScheme] so light and dark dynamic color
 * both read correctly.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * App launcher icon drawn as an iOS-style squircle (22.5% corner radius), clipped
 * edge-to-edge with no tonal container or ring behind it — launcher icons already carry
 * their own safe padding. When [bitmap] is null (not loaded, or the package has no
 * icon) a neutral tonal tile with the app's first grapheme is shown instead, so rows
 * never collapse while icons load.
 */
@Composable
fun AppIconTile(
    bitmap: ImageBitmap?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val shape = RoundedCornerShape(size * 0.225f)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        val monogram = remember(name) { name.monogramOrFallback() }
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = monogram,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Convenience loader for [AppIconTile]: reads the shared [InstalledAppsRepository] icon
 * cache first and only falls back to an IO load when the package hasn't been seen yet.
 * Not part of the component contract — it exists so the picker and the boundaries
 * overview share one icon-loading path.
 */
@Composable
fun AppIconTileForPackage(
    packageName: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) {
        mutableStateOf(InstalledAppsRepository.getCachedIconBitmap(packageName))
    }
    if (bitmap == null) {
        LaunchedEffect(packageName) {
            val loaded = InstalledAppsRepository.getAppIconBitmap(context, packageName)
            if (loaded != null) bitmap = loaded
        }
    }
    AppIconTile(bitmap = bitmap, name = name, modifier = modifier, size = size)
}

/**
 * The single large screen title: headlineMedium Bold with tightened tracking, optional
 * two-line subtitle, and an optional trailing actions row riding at the title's
 * baseline area. Only a minimal 4dp top rhythm is baked in — the Scaffold's window
 * insets own the status-bar protection, so content starts just under the status bar.
 * Horizontal 16dp edge alignment stays the caller's job.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (actions != null) {
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = actions
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Small, quiet section label: titleSmall semibold, sentence case, onSurfaceVariant.
 * 24dp of top rhythm is baked in so sections separate cleanly without callers adding
 * their own gap; the optional [trailing] slot (counts, a small action) rides on the
 * right edge of the label.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.1.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailing
            )
        }
    }
}

/**
 * First grapheme of [this] name, upper-cased, for the fallback icon monogram.
 * Handles blank names ("?") and surrogate pairs (emoji) without crashing.
 */
private fun String.monogramOrFallback(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.codePointAt(0)
    return String(Character.toChars(first)).uppercase()
}

/** Shared finite motion specs and restrained decorative/entrance timing. */
object MotionTokens {
    val SpatialFloat: FiniteAnimationSpec<Float> = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
    /** Slide-offset spec for slideInVertically/slideOutVertically (animates IntOffset). */
    val SpatialOffset: FiniteAnimationSpec<IntOffset> =
        spring<IntOffset>(dampingRatio = 0.8f, stiffness = 380f)
    val SpatialIntSize: FiniteAnimationSpec<IntSize> =
        spring<IntSize>(dampingRatio = 0.8f, stiffness = 380f)
    val FastFloat: FiniteAnimationSpec<Float> = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
    val FadeFloat: FiniteAnimationSpec<Float> = spring<Float>(dampingRatio = 1f, stiffness = 1600f)

    val PressFloat: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 800f)
    val ChevronFloat: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 800f)
    val ProgressFloat: FiniteAnimationSpec<Float> = tween(durationMillis = 500)
    val PulseFloat = tween<Float>(durationMillis = 1200)
    const val PressScale = 0.97f
    val EntranceTravel = 12.dp
    const val StaggerMs = 40L
    const val MaxStaggerMs = 200L
}

/**
 * Small staged-entrance wrapper shared by the non-lazy screens (boundaries
 * overview, strict, permission dialog), home and auth. Elements stagger by 40ms
 * (200ms maximum), then fade/slide up to 12dp with shared spatial/fade springs.
 * Runs on first composition only: once [visible] flips true the element stays
 * revealed across recompositions, so scrolls and state flips never replay it.
 *
 * Content is always composed and laid out. Only layer alpha and translation
 * animate, with no per-frame composition reads or layout jumps. Eligible content
 * starts transparent while waiting for [visible], unless system motion is disabled.
 *
 * Once-per-process gating via [screenKey]: null keeps the legacy behavior
 * (play on each fresh composition); a non-null key plays only the first time
 * that key is seen in this process (tracked in a module-level seen-set), and
 * later compositions render the final state instantly with no animation. This
 * stops entrance cascades replaying on every tab return, since tab switches
 * tear the screens out of composition and reset their remembered flags.
 */
@Composable
fun StaggeredFadeSlide(
    visible: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    screenKey: String? = null,
    content: @Composable () -> Unit,
) {
    // Keep eligibility and animation state together for this screen/index identity.
    // A sibling consuming the seen-set slot must never switch an in-flight branch.
    val eligible = remember(screenKey, index) {
        screenKey == null || !seenEntranceScreens.contains(screenKey)
    }
    val alpha = remember(screenKey, index) { Animatable(if (eligible) 0f else 1f) }
    val travel = remember(screenKey, index) { Animatable(if (eligible) 1f else 0f) }
    val systemMotionEnabled = rememberSystemMotionEnabled()
    LaunchedEffect(visible, screenKey, index, systemMotionEnabled) {
        if (!eligible || !systemMotionEnabled) {
            alpha.snapTo(1f)
            travel.snapTo(0f)
            if (visible && screenKey != null) seenEntranceScreens.add(screenKey)
        } else if (visible) {
            // Consume the process-wide slot up front, so a restart that cancels the
            // in-flight cascade (system animator scale flipping mid-run) cannot replay it.
            if (screenKey != null) seenEntranceScreens.add(screenKey)
            // A Compose animation (rather than delay) respects live motion-scale changes,
            // including a zero-scale override supplied by a host/test.
            val stagger = (index.coerceIn(0, 5) * MotionTokens.StaggerMs)
                .coerceAtMost(MotionTokens.MaxStaggerMs).toInt()
            if (stagger > 0 && coroutineContext[MotionDurationScale]?.scaleFactor != 0f) {
                Animatable(0f).animateTo(1f, tween(durationMillis = stagger))
            }
            launch { travel.animateTo(0f, MotionTokens.SpatialFloat) }
            alpha.animateTo(1f, MotionTokens.FadeFloat)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = travel.value.coerceIn(0f, 1f) * MotionTokens.EntranceTravel.toPx()
        },
    ) {
        content()
    }
}

/**
 * Process-lifetime set of entrance [screenKey] values that have already played
 * their cascade once. Tab switches tear screens out of composition (resetting
 * remembered flags), so this module-level set is the only thing that survives
 * a tab return and keeps entrances to once per process per screen.
 */
private val seenEntranceScreens = mutableSetOf<String>()

/** Observe settings changes, not frames; unregister on leaving composition. */
@Composable
private fun rememberSystemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.applicationContext.contentResolver
    fun readEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)
    var enabled by remember(resolver) { mutableStateOf(readEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readEnabled() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        enabled = readEnabled() // Close the gap between initial read and registration.
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Decorative motion only runs while resumed and both system/Compose motion are enabled. */
@Composable
fun rememberDecorativeMotionEnabled(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val systemEnabled = rememberSystemMotionEnabled()
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return resumed && systemEnabled && (motionScale?.scaleFactor ?: 1f) > 0f
}

/** Read the returned State only inside a draw/graphicsLayer lambda. */
@Composable
fun rememberDecorativePulse(
    initialValue: Float,
    targetValue: Float,
    staticValue: Float = initialValue,
    active: Boolean = true,
    label: String = "decorative-pulse",
): State<Float> {
    val enabled = rememberDecorativeMotionEnabled()
    return if (active && enabled) {
        val transition = rememberInfiniteTransition(label = label)
        transition.animateFloat(
            initialValue = initialValue,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(MotionTokens.PulseFloat, RepeatMode.Reverse),
            label = label,
        )
    } else {
        rememberUpdatedState(staticValue)
    }
}

/**
 * Subtle press feedback (~0.97 scale) for tappable surfaces that own their own
 * [interactionSource] (nav rows, category headers). The animated value is read
 * inside [graphicsLayer] (draw phase), so presses never trigger recomposition
 * storms. Deliberately NOT for toggleable rows or chips: those keep their
 * default M3 indication so press motion never fights toggle semantics.
 */
@Composable
fun pressScaleModifier(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = MotionTokens.PressScale,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = MotionTokens.PressFloat,
        label = "pressScale"
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
