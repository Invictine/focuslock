package com.focuslock.app

import com.focuslock.app.service.FrogBillingPolicy
import org.junit.Assert.assertFalse
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
}
