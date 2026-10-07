package com.focuslock.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

data class PopupShieldTarget(
    val window: InteractiveAppWindow,
    val rectangles: List<WindowBounds>,
    val onOpen: () -> Unit,
)

/** Opaque accessibility overlays clipped to the currently exposed part of app windows. */
class PopupBlockShield(context: Context) {
    private val context = context
    private val entries = linkedMapOf<Key, Entry>()

    val activeRegionCount: Int get() = entries.size

    /** Safe diagnostic string containing no app, account, or domain data. */
    var lastError: String? = null
        private set

    private data class Key(val displayId: Int, val windowId: Int, val bounds: WindowBounds)
    private data class Entry(
        val windowManager: WindowManager,
        val view: FrameLayout,
        val params: WindowManager.LayoutParams,
    )

    /** Must be called on the main thread. Returns false if any overlay could not be shown. */
    fun update(targets: List<PopupShieldTarget>): Boolean {
        if (!onMainThread()) return fail("update_requires_main_thread")
        lastError = null
        val desired = linkedMapOf<Key, PopupShieldTarget>()
        targets.forEach { target ->
            target.rectangles.filterNot { it.isEmpty }.forEach { bounds ->
                desired[Key(target.window.displayId, target.window.id, bounds)] = target
            }
        }

        var complete = true
        val removedKeys = entries.keys.filter { it !in desired.keys }
        removedKeys.forEach { if (!removeEntry(it)) complete = false }

        desired.forEach { (key, target) ->
            val existing = entries[key]
            if (existing != null) {
                updateContent(existing.view, target.onOpen)
                if (!updateLayout(existing, key.bounds)) complete = false
            } else if (!addEntry(key, target)) {
                complete = false
            }
        }
        return complete
    }

    /** Removes every overlay. Must be called on the main thread. */
    fun clear() {
        if (!onMainThread()) {
            fail("clear_requires_main_thread")
            return
        }
        lastError = null
        entries.keys.toList().forEach(::removeEntry)
    }

    private fun addEntry(key: Key, target: PopupShieldTarget): Boolean {
        return try {
            val displayContext = contextForDisplay(target.window.displayId)
                ?: return fail("display_unavailable")
            val windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = layoutParams(key.bounds)
            val view = createShieldView(displayContext, target.onOpen)
            windowManager.addView(view, params)
            entries[key] = Entry(windowManager, view, params)
            true
        } catch (error: Exception) {
            fail("add_failed:${error.javaClass.simpleName}")
        }
    }

    private fun updateLayout(entry: Entry, bounds: WindowBounds): Boolean {
        val next = layoutParams(bounds)
        if (entry.params.x == next.x && entry.params.y == next.y &&
            entry.params.width == next.width && entry.params.height == next.height
        ) return true
        return try {
            entry.params.x = next.x
            entry.params.y = next.y
            entry.params.width = next.width
            entry.params.height = next.height
            entry.windowManager.updateViewLayout(entry.view, entry.params)
            true
        } catch (error: Exception) {
            fail("update_failed:${error.javaClass.simpleName}")
        }
    }

    private fun removeEntry(key: Key): Boolean {
        val entry = entries[key] ?: return true
        try {
            entry.windowManager.removeView(entry.view)
            entries.remove(key)
            return true
        } catch (error: Exception) {
            fail("remove_failed:${error.javaClass.simpleName}")
            return false
        }
    }

    private fun createShieldView(displayContext: Context, onOpen: () -> Unit): FrameLayout {
        val root = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.rgb(15, 20, 29))
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, _ -> true }
            contentDescription = "Blocked by FocusLock"
        }
        val content = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(displayContext, 6), dp(displayContext, 4), dp(displayContext, 6), dp(displayContext, 4))
        }
        val caption = TextView(displayContext).apply {
            text = "Blocked by FocusLock"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            isClickable = false
        }
        val button = TextView(displayContext).apply {
            text = "Open FocusLock"
            setTextColor(Color.rgb(15, 20, 29))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(displayContext, 6), dp(displayContext, 3), dp(displayContext, 6), dp(displayContext, 3))
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = false
            setOnClickListener { runCatching { onOpen() } }
        }
        content.addView(caption, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(button, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun updateContent(view: FrameLayout, onOpen: () -> Unit) {
        val button = (view.getChildAt(0) as? LinearLayout)?.getChildAt(1)
        button?.setOnClickListener { runCatching { onOpen() } }
    }

    private fun contextForDisplay(displayId: Int): Context? {
        // Keep the AccessibilityService context (and its overlay authorization token)
        // on the default display. A display context is needed only for secondary displays.
        if (displayId == Display.DEFAULT_DISPLAY) return context
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val display = manager.getDisplay(displayId) ?: return null
        return context.createDisplayContext(display)
    }

    private fun layoutParams(bounds: WindowBounds) = WindowManager.LayoutParams(
        bounds.width,
        bounds.height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = bounds.left
        y = bounds.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun onMainThread() = Looper.myLooper() == Looper.getMainLooper()

    private fun fail(message: String): Boolean {
        lastError = message
        return false
    }
}
