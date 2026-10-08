package com.focuslock.app.service

/** A half-open rectangle in display coordinates. */
data class WindowBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersect(other: WindowBounds): WindowBounds? {
        val result = WindowBounds(
            maxOf(left, other.left),
            maxOf(top, other.top),
            minOf(right, other.right),
            minOf(bottom, other.bottom),
        )
        return result.takeUnless { it.isEmpty }
    }
}

/** App-owned interactive window data needed for enforcement and shield placement. */
data class InteractiveAppWindow(
    val id: Int,
    val packageName: String,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: WindowBounds,
    val displayId: Int = 0,
    /** System and IME windows occlude apps but must never become enforcement targets. */
    val isAppWindow: Boolean = true,
)

/** Pure window-selection and geometry policy, including system/IME occlusion. */
object InteractiveWindowPolicy {
    private val systemPackages = setOf("android", "com.android.systemui")

    fun foreground(windows: List<InteractiveAppWindow>): InteractiveAppWindow? {
        val appWindows = windows.filter { it.isAppWindow && it.packageName !in systemPackages }
        return appWindows.firstOrNull { it.active }
            ?: appWindows.firstOrNull { it.focused }
    }

    /**
     * Visible pieces of [target] after subtracting every higher-layer window on the
     * same display. Rectangles are disjoint and clipped to the display's coordinate area.
     */
    fun exposedBounds(
        target: InteractiveAppWindow,
        allWindows: List<InteractiveAppWindow>,
        displayBounds: WindowBounds = WindowBounds(Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2),
    ): List<WindowBounds> {
        val initial = target.bounds.intersect(displayBounds) ?: return emptyList()
        var visible = listOf(initial)
        val occluders = allWindows.asSequence()
            .filter { it.id != target.id && it.displayId == target.displayId }
            .filter { it.layer > target.layer && !it.bounds.isEmpty }
            .sortedByDescending { it.layer }
            .map { it.bounds }
            .toList()

        for (occluder in occluders) {
            visible = visible.flatMap { subtract(it, occluder) }
            if (visible.isEmpty()) break
        }
        return visible
    }

    fun isMultiWindow(
        target: InteractiveAppWindow,
        windows: List<InteractiveAppWindow>,
        displayBounds: WindowBounds,
    ): Boolean {
        val displayWindows = windows.filter {
            it.displayId == target.displayId && it.isAppWindow && it.packageName !in systemPackages && !it.bounds.isEmpty
        }
        val hasMultipleAppWindows = displayWindows.map { it.id to it.packageName }.distinct().size >= 2
        val displayWidth = displayBounds.width
        val displayHeight = displayBounds.height
        val targetIsNarrow = displayWidth > 0 && target.bounds.width.toLong() * 100 < displayWidth.toLong() * 85
        val targetIsShort = displayHeight > 0 && target.bounds.height.toLong() * 100 < displayHeight.toLong() * 70
        return hasMultipleAppWindows || targetIsNarrow || targetIsShort
    }

    private fun subtract(source: WindowBounds, cover: WindowBounds): List<WindowBounds> {
        val overlap = source.intersect(cover) ?: return listOf(source)
        return buildList(4) {
            addIfVisible(WindowBounds(source.left, source.top, source.right, overlap.top))
            addIfVisible(WindowBounds(source.left, overlap.bottom, source.right, source.bottom))
            addIfVisible(WindowBounds(source.left, overlap.top, overlap.left, overlap.bottom))
            addIfVisible(WindowBounds(overlap.right, overlap.top, source.right, overlap.bottom))
        }
    }

    private fun MutableList<WindowBounds>.addIfVisible(bounds: WindowBounds) {
        if (!bounds.isEmpty) add(bounds)
    }
}
