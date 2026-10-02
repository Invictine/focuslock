package com.focuslock.app

import com.focuslock.app.ui.apps.isPermanentDomain
import com.focuslock.app.ui.apps.isPermanentPackage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryPermanentFilteringTest {
    @Test
    fun packageExclusionIgnoresCaseAndSurroundingSpace() {
        assertTrue(isPermanentPackage(" Com.Example.App ", setOf("com.example.app")))
        assertFalse(isPermanentPackage("com.example.other", setOf("com.example.app")))
    }

    @Test
    fun websiteExclusionIncludesSubdomainsWithoutMatchingUnrelatedHosts() {
        val permanent = setOf("example.com")
        assertTrue(isPermanentDomain("example.com", permanent))
        assertTrue(isPermanentDomain("m.example.com", permanent))
        assertTrue(isPermanentDomain("deep.sub.example.com", permanent))
        assertFalse(isPermanentDomain("notexample.com", permanent))
        assertFalse(isPermanentDomain("example.com.other.net", permanent))
    }
}
