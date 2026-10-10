package com.focuslock.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FrogHomeLauncherTest {

    private val niagara = "bitpit.launcher/.ui.HomeActivity"
    private val stockLauncher = "com.android.launcher3/.Launcher"
    private val focusLock = "com.focuslock.app/com.focuslock.app.ui.blocker.FrogHomeActivity"
    private val removedLauncher = "com.example.removed/.HomeActivity"

    @Test
    fun savedNiagaraWinsEvenWhenStockLauncherIsFirst() {
        assertEquals(
            niagara,
            FrogHomeLauncher.chooseRegularHome(
                saved = niagara,
                current = stockLauncher,
                validCandidates = linkedSetOf(stockLauncher, niagara)
            )
        )
    }

    @Test
    fun invalidFocusLockCurrentDoesNotReplaceSavedNiagara() {
        assertEquals(
            niagara,
            FrogHomeLauncher.chooseRegularHome(
                saved = niagara,
                current = focusLock,
                validCandidates = setOf(niagara)
            )
        )
    }

    @Test
    fun invalidSavedLauncherUsesValidCurrentNiagara() {
        assertEquals(
            niagara,
            FrogHomeLauncher.chooseRegularHome(
                saved = removedLauncher,
                current = niagara,
                validCandidates = setOf(niagara)
            )
        )
    }

    @Test
    fun multipleValidCandidatesWithoutSavedOrCurrentReturnsNull() {
        assertEquals(
            null,
            FrogHomeLauncher.chooseRegularHome(
                saved = null,
                current = null,
                validCandidates = setOf(stockLauncher, niagara)
            )
        )
    }

    @Test
    fun noValidCandidatesReturnsNull() {
        assertEquals(
            null,
            FrogHomeLauncher.chooseRegularHome(
                saved = null,
                current = null,
                validCandidates = emptySet()
            )
        )
    }

    @Test
    fun singleValidCandidateIsUsedAsFallback() {
        assertEquals(
            niagara,
            FrogHomeLauncher.chooseRegularHome(
                saved = null,
                current = null,
                validCandidates = setOf(niagara)
            )
        )
    }

    @Test
    fun unchangedSavedLauncherSkipsFallbackInventoryRefresh() {
        assertEquals(false, FrogHomeLauncher.shouldCaptureFallback(niagara, niagara, "com.focuslock.app"))
    }

    @Test
    fun focusLockAsCurrentLauncherDoesNotReplaceSavedFallback() {
        assertEquals(false, FrogHomeLauncher.shouldCaptureFallback(focusLock, niagara, "com.focuslock.app"))
    }

    @Test
    fun changedExternalLauncherRefreshesFallback() {
        assertEquals(true, FrogHomeLauncher.shouldCaptureFallback(stockLauncher, niagara, "com.focuslock.app"))
    }
}
