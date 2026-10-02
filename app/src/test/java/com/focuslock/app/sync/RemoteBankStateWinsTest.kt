package com.focuslock.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBankStateWinsTest {
    @Test
    fun `newer remote aggregate applies when local bank is clean`() {
        assertTrue(remoteBankStateWins(
            remotePresent = true,
            remoteUpdatedAt = 200,
            localUpdatedAt = 100,
            localDirtySinceSync = false,
        ))
    }

    @Test
    fun `newer remote aggregate cannot erase local changes made since last sync`() {
        assertFalse(remoteBankStateWins(
            remotePresent = true,
            remoteUpdatedAt = 300,
            localUpdatedAt = 200,
            localDirtySinceSync = true,
        ))
    }

    @Test
    fun `missing or equal remote state does not win`() {
        assertFalse(remoteBankStateWins(false, 300, 200, false))
        assertFalse(remoteBankStateWins(true, 200, 200, false))
    }

    @Test
    fun `durable acknowledged stamp catches edits after sync step but before cycle end`() {
        // LAST_SYNC_TIMESTAMP can be stamped after the state step. The separate
        // acknowledged state stamp must keep this bank dirty despite that later clock.
        assertTrue(bankStateIsDirty(
            localUpdatedAt = 200,
            syncedUpdatedAt = 100,
            lastSuccessfulSync = 300,
            hasMeaningfulLocalState = true,
        ))
        assertFalse(bankStateIsDirty(
            localUpdatedAt = 100,
            syncedUpdatedAt = 100,
            lastSuccessfulSync = 300,
            hasMeaningfulLocalState = true,
        ))
    }

    @Test
    fun `legacy installations fall back to last sync heuristic`() {
        assertFalse(bankStateIsDirty(100, null, 200, true))
        assertTrue(bankStateIsDirty(300, null, 200, false))
        assertTrue(bankStateIsDirty(0, null, 0, true))
    }
}
