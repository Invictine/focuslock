package com.focuslock.app

import com.focuslock.app.service.FrogBillingPolicy
import com.focuslock.app.service.FrogBillingWindowTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrogBillingPolicyTest {
    @Test
    fun observedPlayBillingActivityIsExempt() {
        assertTrue(
            FrogBillingPolicy.isBillingWindow(
                "com.android.vending",
                "com.google.android.finsky.billing.acquire.LockToPortraitUiBuilderHostActivity",
            ),
        )
    }

    @Test
    fun otherOfficialPlayBillingActivityIsExempt() {
        assertTrue(
            FrogBillingPolicy.isBillingWindow(
                "com.android.vending",
                "com.google.android.finsky.billing.SomeOtherBillingActivity",
            ),
        )
    }

    @Test
    fun playStoreMainActivityIsNotExempt() {
        assertFalse(
            FrogBillingPolicy.isBillingWindow(
                "com.android.vending",
                "com.google.android.finsky.activities.AssetBrowserActivity",
            ),
        )
    }

    @Test
    fun anotherPackageWithBillingNamedWindowIsNotExempt() {
        assertFalse(
            FrogBillingPolicy.isBillingWindow(
                "com.example.fakebilling",
                "com.google.android.finsky.billing.SomeBillingActivity",
            ),
        )
    }

    @Test
    fun missingOrGenericWindowClassIsNotExempt() {
        assertFalse(FrogBillingPolicy.isBillingWindow("com.android.vending", null))
        assertFalse(FrogBillingPolicy.isBillingWindow("com.android.vending", "android.app.Dialog"))
    }

    @Test
    fun nearPrefixSpellingIsNotExempt() {
        assertFalse(
            FrogBillingPolicy.isBillingWindow(
                "com.android.vending",
                "com.google.android.finsky.billingevil.SomeActivity",
            ),
        )
        assertFalse(
            FrogBillingPolicy.isBillingWindow(
                "com.android.vending",
                "com.google.android.finsky.billin.SomeActivity",
            ),
        )
    }

    @Test
    fun genericEventsAndInterveningTickTickKeepBillingClassWithinStoreWindow() {
        val tracker = FrogBillingWindowTracker()
        val billingClass = "com.google.android.finsky.billing.acquire.LockToPortraitUiBuilderHostActivity"

        assertEquals(
            billingClass,
            tracker.observe("com.android.vending", 12, billingClass),
        )
        assertEquals("com.ticktick.task.ProxyBillingActivity", tracker.observe("com.ticktick.task", 88, "com.ticktick.task.ProxyBillingActivity"))
        assertEquals(billingClass, tracker.observe("com.android.vending", 12, "android.widget.FrameLayout"))
        assertEquals(billingClass, tracker.observe("com.android.vending", 12, null))
        assertTrue(FrogBillingPolicy.isBillingWindow("com.android.vending", billingClass))
    }

    @Test
    fun newStoreWindowDoesNotInheritPreviousBillingClass() {
        val tracker = FrogBillingWindowTracker()
        val billingClass = "com.google.android.finsky.billing.acquire.LockToPortraitUiBuilderHostActivity"
        tracker.observe("com.android.vending", 12, billingClass)

        val currentClass = tracker.observe("com.android.vending", 13, "android.widget.FrameLayout")
        assertEquals("android.widget.FrameLayout", currentClass)
        assertFalse(FrogBillingPolicy.isBillingWindow("com.android.vending", currentClass))
    }

    @Test
    fun qualifiedMainStoreClassRevokesBillingForSameWindow() {
        val tracker = FrogBillingWindowTracker()
        tracker.observe(
            "com.android.vending",
            12,
            "com.google.android.finsky.billing.acquire.LockToPortraitUiBuilderHostActivity",
        )

        val currentClass = tracker.observe(
            "com.android.vending",
            12,
            "com.google.android.finsky.activities.AssetBrowserActivity",
        )
        assertEquals("com.google.android.finsky.activities.AssetBrowserActivity", currentClass)
        assertFalse(FrogBillingPolicy.isBillingWindow("com.android.vending", currentClass))
    }

    @Test
    fun invalidWindowIdsNeverRetainBillingClass() {
        val tracker = FrogBillingWindowTracker()
        val billingClass = "com.google.android.finsky.billing.acquire.LockToPortraitUiBuilderHostActivity"
        tracker.observe("com.android.vending", -1, billingClass)

        val currentClass = tracker.observe("com.android.vending", -1, "android.widget.FrameLayout")
        assertEquals("android.widget.FrameLayout", currentClass)
        assertFalse(FrogBillingPolicy.isBillingWindow("com.android.vending", currentClass))

        val nullClass = tracker.observe("com.android.vending", -1, null)
        assertNull(nullClass)
        assertFalse(FrogBillingPolicy.isBillingWindow("com.android.vending", nullClass))
    }
}
