package com.focuslock.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveWindowPolicyTest {
    private val display = WindowBounds(0, 0, 1_000, 800)

    @Test
    fun foregroundPrefersActiveWindowThenFocusedAndSkipsSystemWindows() {
        val active = window(1, "allowed", active = true)
        val focused = window(2, "blocked", focused = true)
        assertEquals(active, InteractiveWindowPolicy.foreground(listOf(active, focused)))
        assertEquals(focused, InteractiveWindowPolicy.foreground(listOf(active.copy(active = false), focused)))
        assertEquals(active, InteractiveWindowPolicy.foreground(listOf(
            window(3, "android", active = true, focused = true), active,
        )))
        assertNull(InteractiveWindowPolicy.foreground(listOf(window(4, "com.android.systemui", active = true))))
        assertNull(InteractiveWindowPolicy.foreground(emptyList()))
    }

    @Test
    fun exposedBoundsSubtractsOverlappingHigherLayerWindowIntoDisjointPieces() {
        val target = window(1, "blocked", bounds = WindowBounds(0, 0, 100, 100), layer = 1)
        val allowedOverlay = window(2, "allowed", bounds = WindowBounds(25, 20, 75, 80), layer = 2)
        assertEquals(
            listOf(
                WindowBounds(0, 0, 100, 20),
                WindowBounds(0, 80, 100, 100),
                WindowBounds(0, 20, 25, 80),
                WindowBounds(75, 20, 100, 80),
            ),
            InteractiveWindowPolicy.exposedBounds(target, listOf(target, allowedOverlay), display),
        )
    }

    @Test
    fun exposedAreaRemainsDisjointAfterSubtractingOverlappingAllowedWindows() {
        val target = window(1, "blocked", bounds = WindowBounds(0, 0, 100, 100), layer = 1)
        val firstAllowed = window(2, "allowed.one", bounds = WindowBounds(20, 20, 70, 70), layer = 2)
        val secondAllowed = window(3, "allowed.two", bounds = WindowBounds(50, 0, 90, 50), layer = 3)
        val exposed = InteractiveWindowPolicy.exposedBounds(
            target,
            listOf(target, firstAllowed, secondAllowed),
            WindowBounds(0, 0, 100, 100),
        )

        assertEquals(6_100L, exposed.sumOf { it.width.toLong() * it.height })
        exposed.forEachIndexed { index, first ->
            exposed.drop(index + 1).forEach { second -> assertNull(first.intersect(second)) }
        }
    }

    @Test
    fun movedAndResizedTargetUsesCurrentBoundsAndClipsToDisplay() {
        val moved = window(7, "blocked", bounds = WindowBounds(900, 100, 1_100, 500))
        assertEquals(
            listOf(WindowBounds(900, 100, 1_000, 500)),
            InteractiveWindowPolicy.exposedBounds(moved, listOf(moved), display),
        )
        val resized = moved.copy(bounds = WindowBounds(100, 100, 300, 250))
        assertEquals(listOf(resized.bounds), InteractiveWindowPolicy.exposedBounds(resized, listOf(resized), display))
    }

    @Test
    fun closedTargetAndOccludersAreNotIncluded() {
        val target = window(1, "blocked", bounds = WindowBounds(0, 0, 100, 100), layer = 1)
        val closed = window(1, "blocked", bounds = WindowBounds(0, 0, 0, 0), layer = 1)
        val overlay = window(2, "allowed", bounds = WindowBounds(0, 0, 100, 100), layer = 2)
        assertTrue(InteractiveWindowPolicy.exposedBounds(closed, listOf(closed), display).isEmpty())
        assertTrue(InteractiveWindowPolicy.exposedBounds(target, listOf(target, overlay), display).isEmpty())
        assertTrue(InteractiveWindowPolicy.exposedBounds(
            target.copy(bounds = WindowBounds(10, 10, 10, 50)),
            listOf(target),
            display,
        ).isEmpty())
    }

    @Test
    fun multipleWindowsAndDisplaysAreHandledIndependently() {
        val target = window(1, "blocked", bounds = WindowBounds(0, 0, 1_000, 800), layer = 1, displayId = 0)
        val otherDisplay = window(2, "allowed", bounds = WindowBounds(0, 0, 1_000, 800), layer = 2, displayId = 1)
        assertFalse(InteractiveWindowPolicy.isMultiWindow(target, listOf(target, otherDisplay), display))
        assertEquals(listOf(target.bounds), InteractiveWindowPolicy.exposedBounds(target, listOf(target, otherDisplay), display))

        val sameDisplay = otherDisplay.copy(displayId = 0)
        assertTrue(InteractiveWindowPolicy.isMultiWindow(target, listOf(target, sameDisplay), display))
        assertEquals(emptyList<WindowBounds>(), InteractiveWindowPolicy.exposedBounds(target, listOf(target, sameDisplay), display))
    }

    @Test
    fun multiWindowThresholdsDetectNarrowAndShortTargets() {
        val full = window(1, "blocked", bounds = display)
        assertFalse(InteractiveWindowPolicy.isMultiWindow(full, listOf(full), display))
        assertTrue(InteractiveWindowPolicy.isMultiWindow(
            full.copy(bounds = WindowBounds(0, 0, 849, 800)), listOf(full.copy(bounds = WindowBounds(0, 0, 849, 800))), display,
        ))
        assertTrue(InteractiveWindowPolicy.isMultiWindow(
            full.copy(bounds = WindowBounds(0, 0, 1_000, 559)), listOf(full.copy(bounds = WindowBounds(0, 0, 1_000, 559))), display,
        ))
    }

    private fun window(
        id: Int,
        packageName: String,
        active: Boolean = false,
        focused: Boolean = false,
        bounds: WindowBounds = display,
        layer: Int = 0,
        displayId: Int = 0,
    ) = InteractiveAppWindow(id, packageName, layer, active, focused, bounds, displayId)
}
