package com.focuslock.app.ui.components

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import kotlinx.coroutines.delay

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

/**
 * Shared motion language (M3 Expressive, no new deps): spatial default
 * `spring(0.8, 380)`, fast `spring(0.6, 800)`, fade/color `spring(1, 1600)`.
 * Entrances are `slideInVertically { it / 4 } + fadeIn`, staggered ~60ms per
 * element. Callers cap the stagger index at ~6 items so the tail never lags.
 */
object MotionTokens {
    val SpatialFloat: FiniteAnimationSpec<Float> = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
    /** Slide-offset spec for slideInVertically/slideOutVertically (animates IntOffset). */
    val SpatialOffset: FiniteAnimationSpec<IntOffset> =
        spring<IntOffset>(dampingRatio = 0.8f, stiffness = 380f)
    val SpatialIntSize: FiniteAnimationSpec<IntSize> =
        spring<IntSize>(dampingRatio = 0.8f, stiffness = 380f)
    val FastFloat: FiniteAnimationSpec<Float> = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
    val FadeFloat: FiniteAnimationSpec<Float> = spring<Float>(dampingRatio = 1f, stiffness = 1600f)

    /** Per-element entrance stagger; callers cap the index at ~6 items. */
    const val StaggerMs = 60L
}

/**
 * Small staged-entrance wrapper shared by the non-lazy screens (boundaries
 * overview, strict, permission dialog). Each element waits ~60ms after the
 * previous one, then fades/slides in with the shared spatial/fade springs.
 * Runs on first composition only: once [visible] flips true the element stays
 * revealed across recompositions, so scrolls and state flips never replay it.
 */
@Composable
fun StaggeredFadeSlide(
    visible: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            if (index > 0) delay(index * MotionTokens.StaggerMs)
            revealed = true
        }
    }
    AnimatedVisibility(
        visible = revealed,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = MotionTokens.SpatialOffset,
            initialOffsetY = { it / 4 }
        ) + fadeIn(animationSpec = MotionTokens.FadeFloat),
    ) {
        content()
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
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = MotionTokens.FastFloat,
        label = "pressScale"
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
