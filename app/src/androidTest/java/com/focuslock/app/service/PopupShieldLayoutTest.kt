package com.focuslock.app.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the native shield at compact overlay sizes without creating a window. */
@RunWith(AndroidJUnit4::class)
class PopupShieldLayoutTest {
    @Test
    fun narrowTallShieldKeepsHeadlineAndActionInsideBounds() {
        onMain {
            val fixture = createShield(widthDp = 220, heightDp = 430)
            val headline = visibleViews(fixture.view).filterIsInstance<TextView>()
                .firstOrNull { it !is Button && it.text.toString().contains("blocked", ignoreCase = true) }
            val action = actionButton(fixture.view)

            assertNotNull("A blocked-state headline must remain visible", headline)
            assertTrue("The headline must have laid-out content", headline!!.width > 0 && headline.height > 0)
            assertInside(fixture.view, headline)
            assertNotNull("The primary action must remain available", action)
            assertInside(fixture.view, action!!)
            assertTrue("The action must retain a 48dp touch target", action.width >= dp(fixture.context, 48))
            assertTrue("The action must retain a 48dp touch target", action.height >= dp(fixture.context, 48))
        }
    }

    @Test
    fun largeFontAtMediumSizeDoesNotOverlapOrEscapeShield() {
        onMain {
            val fixture = createShield(widthDp = 320, heightDp = 500, fontScale = 2f)
            val leaves = visibleLeaves(fixture.view)
            assertTrue("The shield should contain visible content", leaves.isNotEmpty())
            leaves.forEach { assertInside(fixture.view, it) }

            for (i in leaves.indices) {
                for (j in i + 1 until leaves.size) {
                    val firstView = leaves[i]
                    val secondView = leaves[j]
                    val first = rootBounds(fixture.view, firstView)
                    val second = rootBounds(fixture.view, secondView)
                    assertTrue(
                        "Visible children overlap: ${firstView.javaClass.simpleName} and ${secondView.javaClass.simpleName}",
                        first.right <= second.left || second.right <= first.left ||
                            first.bottom <= second.top || second.bottom <= first.top,
                    )
                }
            }
        }
    }

    @Test
    fun thinShieldKeepsCompactActionOpaqueAndUsesLatestCallback() {
        onMain {
            val fixture = createShield(widthDp = 220, heightDp = 72)
            val originalCalls = intArrayOf(0)
            val latestCalls = intArrayOf(0)
            fixture.view.updateAction { originalCalls[0]++ }
            fixture.view.updateAction { latestCalls[0]++ }

            assertTrue("The shield must intercept taps while compact", fixture.view.isClickable)
            val background = fixture.view.background as? ColorDrawable
            assertNotNull("The thin shield must retain a solid opaque background", background)
            assertEquals(255, background!!.alpha)

            val action = actionButton(fixture.view)
            assertNotNull("The compact action must fit in the thin region", action)
            assertInside(fixture.view, action!!)
            assertTrue("The compact action must retain a 48dp touch target", action.width >= dp(fixture.context, 48))
            assertTrue("The compact action must retain a 48dp touch target", action.height >= dp(fixture.context, 48))
            assertTrue("The compact action must be clickable", action.isClickable && action.performClick())
            assertEquals(0, originalCalls[0])
            assertEquals(1, latestCalls[0])
        }
    }

    @Test
    fun tinyShieldRemainsOpaqueAndRootTapUsesLatestCallback() {
        onMain {
            val fixture = createShield(widthDp = 24, heightDp = 80)
            val originalCalls = intArrayOf(0)
            val latestCalls = intArrayOf(0)
            fixture.view.updateAction { originalCalls[0]++ }
            fixture.view.updateAction { latestCalls[0]++ }

            assertTrue("The tiny shield must intercept taps", fixture.view.isClickable)
            val background = fixture.view.background as? ColorDrawable
            assertNotNull("The tiny shield must retain a solid opaque background", background)
            assertEquals(255, background!!.alpha)
            assertTrue("A tap on the tiny shield must open FocusLock", fixture.view.performClick())
            assertEquals(0, originalCalls[0])
            assertEquals(1, latestCalls[0])
        }
    }

    private data class Fixture(val context: Context, val view: PopupShieldView)

    private fun createShield(widthDp: Int, heightDp: Int, fontScale: Float = 1f): Fixture {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply { this.fontScale = fontScale }
        val context = target.createConfigurationContext(configuration)
        val view = PopupShieldView(context) {}
        val widthPx = dp(context, widthDp)
        val heightPx = dp(context, heightDp)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        return Fixture(context, view)
    }

    private fun actionButton(root: View): Button? =
        visibleViews(root).filterIsInstance<Button>().firstOrNull {
            it.text.toString().contains("Open FocusLock", ignoreCase = true)
        }

    private fun allViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(allViews(root.getChildAt(index)))
        }
    }

    private fun visibleViews(root: View): List<View> = allViews(root).filter { view ->
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.visibility != View.VISIBLE) return@filter false
            if (ancestor === root) return@filter true
            ancestor = ancestor.parent as? View
        }
        false
    }

    private fun visibleLeaves(root: View): List<View> = visibleViews(root).filter { view ->
        view !== root && !(view is ViewGroup && view.childCount > 0)
    }

    private fun assertInside(root: View, child: View) {
        val bounds = rootBounds(root, child)
        assertTrue("Child is outside the shield's horizontal bounds", bounds.left >= 0 && bounds.right <= root.width)
        assertTrue("Child is outside the shield's vertical bounds", bounds.top >= 0 && bounds.bottom <= root.height)
    }

    private fun rootBounds(root: View, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        (root as ViewGroup).offsetDescendantRectToMyCoords(child, it)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
