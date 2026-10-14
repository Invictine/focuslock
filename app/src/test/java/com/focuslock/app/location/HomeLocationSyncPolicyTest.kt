package com.focuslock.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLocationSyncPolicyTest {
    private val home = HomePlace("Home", 28.6139, 77.209, 150f)
    private val office = HomePlace("Office", 28.4595, 77.0266, 200f)

    @Test
    fun newerOfflineEditUploadsAfterReadingCurrentRevision() {
        assertTrue(shouldUploadHomeLocation(
            isDirty = true,
            localPlace = office,
            localUpdatedAt = 300,
            remotePlace = home,
            remoteUpdatedAt = 200,
        ))
    }

    @Test
    fun newerRemoteEditWinsAndMatchingRemoteValueAcknowledgesPendingEdit() {
        assertFalse(shouldUploadHomeLocation(true, office, 200, home, 300))
        assertFalse(shouldUploadHomeLocation(true, home, 200, home, 300))
    }

    @Test
    fun cleanOrTiedLocalStateDoesNotOverwriteRemote() {
        assertFalse(shouldUploadHomeLocation(false, office, 400, home, 100))
        assertFalse(shouldUploadHomeLocation(true, office, 100, home, 100))
    }
}
