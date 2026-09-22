package com.focuslock.app

import com.focuslock.app.data.repository.PermanentBlockPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermanentBlockPolicyTest {
    private val protected = setOf("com.focuslock.app", "com.android.systemui", "com.example.launcher")

    @Test
    fun enforcesPermanentPackageRegardlessOfOrdinaryBoundaryState() {
        assertTrue(
            PermanentBlockPolicy.shouldEnforce(
                packageName = " com.instagram.android ",
                permanentPackages = setOf("com.instagram.android"),
                protectedPackages = protected,
            )
        )
    }

    @Test
    fun protectedRecoveryPackageCannotBeEnforced() {
        assertFalse(
            PermanentBlockPolicy.shouldEnforce(
                packageName = "com.example.launcher",
                permanentPackages = setOf("com.example.launcher"),
                protectedPackages = protected,
            )
        )
    }

    @Test
    fun nullOrBlankMetadataFailsClosedForPolicyWithoutCreatingARealBlock() {
        assertFalse(PermanentBlockPolicy.shouldEnforce(null, setOf("com.instagram.android"), emptySet()))
        assertFalse(PermanentBlockPolicy.shouldEnforce(" ", setOf("com.instagram.android"), emptySet()))
    }
}
