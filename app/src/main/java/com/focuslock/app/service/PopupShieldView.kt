package com.focuslock.app.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.focuslock.app.ui.theme.resolveFocusLockColorScheme

/** Native, theme-aware content for an opaque accessibility overlay. */
internal class PopupShieldView(
    context: Context,
    private var onOpen: () -> Unit,
) : FrameLayout(context) {
    private var density = resources.displayMetrics.density
    private var colors = resolveFocusLockColorScheme(
        context = context,
        darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    )
    private val compact = LinearLayout(context)
    private val full = LinearLayout(context)
    private val iconSurface = FrameLayout(context)
    private val icon = ImageView(context)
    private val brand = TextView(context)
    private val headline = TextView(context)
    private val supporting = TextView(context)
    private val compactLabel = TextView(context)
    private val action = Button(context)
    private lateinit var compactAction: Button
    private var tiny = false

    init {
        setBackgroundColor(colors.surface.toArgb())
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "App blocked by FocusLock. Open FocusLock."
        setOnClickListener { if (tiny) openFocusLock() }

        full.orientation = LinearLayout.VERTICAL
        full.gravity = Gravity.CENTER
        full.setPadding(dp(24), dp(20), dp(24), dp(20))

        iconSurface.background = rounded(colors.primaryContainer.toArgb(), dp(28))
        iconSurface.contentDescription = null
        iconSurface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.apply {
            setImageDrawable(LockIconDrawable(colors.onPrimaryContainer.toArgb()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        iconSurface.addView(icon, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        full.addView(iconSurface, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(20) })

        brand.apply {
            text = "FOCUSLOCK"
            setTextColor(colors.primary.toArgb())
            textSize = 11f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        full.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

        headline.text = "App is blocked"
        headline.setTextColor(colors.onSurface.toArgb())
        headline.textSize = 22f
        headline.gravity = Gravity.CENTER
        headline.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        headline.maxLines = 2
        headline.includeFontPadding = true
        full.addView(headline, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })

        supporting.text = "Your boundary is active"
        supporting.setTextColor(colors.onSurfaceVariant.toArgb())
        supporting.textSize = 14f
        supporting.gravity = Gravity.CENTER
        supporting.maxLines = 2
        full.addView(supporting, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22) })

        configureAction()
        full.addView(action, LinearLayout.LayoutParams(-2, -2))
        addView(full, LayoutParams(dp(320), -2, Gravity.CENTER))

        compact.orientation = LinearLayout.HORIZONTAL
        compact.gravity = Gravity.CENTER_VERTICAL
        compact.setPadding(dp(12), dp(8), dp(12), dp(8))
        compactLabel.apply {
            text = "App is blocked"
            setTextColor(colors.onSurface.toArgb())
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        compactLabel.visibility = View.GONE
        compact.addView(compactLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        compactAction = makeAction()
        compact.addView(compactAction, LinearLayout.LayoutParams(-2, -2))
        addView(compact, LayoutParams(-1, -1))
        compact.visibility = View.GONE
    }

    fun updateAction(callback: () -> Unit) {
        onOpen = callback
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val widthDp = availableWidth / density
        val compactLabelVisible = widthDp >= 280f
        compactLabel.visibility = if (compactLabelVisible) View.VISIBLE else View.GONE
        (compactLabel.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.marginEnd = if (compactLabelVisible) dp(12) else 0
            compactLabel.layoutParams = params
        }
        val fullWidth = minOf(availableWidth, dp(320))
        (full.layoutParams as? LayoutParams)?.let { params ->
            if (params.width != fullWidth) {
                params.width = fullWidth
                full.layoutParams = params
            }
        }

        // Measure the full content at the available width with an unbounded height. This
        // naturally includes text scaling and line wrapping in the fit decision.
        full.measure(
            MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        compact.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        tiny = widthDp < 160f || compact.measuredHeight > availableHeight
        val showFull = !tiny && widthDp >= 200f && full.measuredHeight <= availableHeight
        full.visibility = if (showFull) View.VISIBLE else View.GONE
        compact.visibility = if (!tiny && !showFull) View.VISIBLE else View.GONE
        contentDescription = if (tiny) "App blocked by FocusLock. Tap to open FocusLock." else null
        importantForAccessibility = if (tiny) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setOnClickListener { if (tiny) openFocusLock() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = resources.displayMetrics.density
        colors = resolveFocusLockColorScheme(
            context = context,
            darkTheme = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        refreshThemeAndTypography()
        requestLayout()
    }

    private fun configureAction() {
        action.text = "Open FocusLock"
        action.textSize = 14f
        action.setTextColor(colors.onPrimary.toArgb())
        action.minHeight = dp(48)
        action.minimumHeight = dp(48)
        action.minWidth = dp(48)
        action.isAllCaps = false
        action.setPadding(dp(12), dp(4), dp(12), dp(4))
        action.setSingleLine(false)
        action.maxLines = 2
        action.gravity = Gravity.CENTER
        action.stateListAnimator = null
        action.background = actionBackground()
        action.setOnClickListener { openFocusLock() }
        action.contentDescription = "Open FocusLock"
    }

    private fun makeAction() = Button(context).apply {
        text = "Open FocusLock"
        textSize = 14f
        setTextColor(colors.onPrimary.toArgb())
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        isAllCaps = false
        setPadding(dp(12), dp(4), dp(12), dp(4))
        setSingleLine(false)
        maxLines = 2
        gravity = Gravity.CENTER
        stateListAnimator = null
        background = actionBackground()
        contentDescription = "Open FocusLock"
        setOnClickListener { openFocusLock() }
    }

    private fun actionBackground(): RippleDrawable {
        val base = rounded(colors.primary.toArgb(), dp(24))
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(colors.onPrimary.copy(alpha = 0.16f).toArgb()),
            base,
            rounded(colors.onPrimary.toArgb(), dp(24)),
        )
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun refreshThemeAndTypography() {
        setBackgroundColor(colors.surface.toArgb())
        iconSurface.background = rounded(colors.primaryContainer.toArgb(), dp(28))
        icon.setImageDrawable(LockIconDrawable(colors.onPrimaryContainer.toArgb()))
        brand.setTextColor(colors.primary.toArgb())
        brand.setTextSize(11f)
        headline.setTextColor(colors.onSurface.toArgb())
        headline.setTextSize(22f)
        supporting.setTextColor(colors.onSurfaceVariant.toArgb())
        supporting.setTextSize(14f)
        compactLabel.setTextColor(colors.onSurface.toArgb())
        compactLabel.setTextSize(16f)
        configureAction()
        compactAction.setTextColor(colors.onPrimary.toArgb())
        compactAction.setTextSize(14f)
        compactAction.background = actionBackground()
        compactAction.setPadding(dp(12), dp(4), dp(12), dp(4))
        full.setPadding(dp(24), dp(20), dp(24), dp(20))
        compact.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun openFocusLock() {
        runCatching { onOpen() }
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()
}

private class LockIconDrawable(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2.1f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        val save = canvas.save()
        canvas.translate(bounds.left + (bounds.width() - 24f * scale) / 2f, bounds.top + (bounds.height() - 24f * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.drawRoundRect(5f, 10f, 19f, 21f, 2.5f, 2.5f, paint)
        val shackle = Path().apply {
            moveTo(8f, 10f)
            lineTo(8f, 7.5f)
            cubicTo(8f, 2.7f, 16f, 2.7f, 16f, 7.5f)
            lineTo(16f, 10f)
        }
        canvas.drawPath(shackle, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
