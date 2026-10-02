package com.focuslock.app.service

import com.focuslock.app.data.model.BlockedApp
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundaryLeisureUsageTest {
    private fun app(pkg: String, blocked: Boolean, permanent: Boolean = false) =
        BlockedApp(packageName = pkg, appName = pkg, isBlocked = blocked, isPermanent = permanent)

    @Test fun sumsOnlySelectedAppsAndDeduplicatesBeforeConversion() {
        val selected = listOf(app("a", true), app("a", true), app("b", true))
        val millis = BoundaryLeisureUsage.foregroundMillis(
            selected,
            mapOf("a" to 59_999L, "b" to 2_001L, "unselected" to 900_000L)
        )
        assertEquals(62_000L, millis)
        assertEquals(62L, millis / 1_000L)
    }

    @Test fun permanentAppsCountEvenWhenNotNormallyBlocked() {
        val selected = listOf(app("permanent", blocked = false, permanent = true))
        assertEquals(12_500L, BoundaryLeisureUsage.foregroundMillis(selected, mapOf("permanent" to 12_500L)))
    }

    @Test fun disabledNonPermanentAppsAndUnselectedPackagesAreIgnored() {
        val selected = listOf(app("disabled", blocked = false), app("selected", blocked = true))
        assertEquals(8_000L, BoundaryLeisureUsage.foregroundMillis(
            selected,
            mapOf("disabled" to 30_000L, "selected" to 8_000L, "other" to 40_000L)
        ))
    }

    @Test fun emptySelectionAndMissingUsageRowsAreZero() {
        assertEquals(0L, BoundaryLeisureUsage.foregroundMillis(emptyList(), mapOf("a" to 5_000L)))
        assertEquals(0L, BoundaryLeisureUsage.foregroundMillis(listOf(app("a", true)), emptyMap()))
    }
}
