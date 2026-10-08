package com.focuslock.app

import com.focuslock.app.updates.updateFailureMessage
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFailureMessageTest {
    @Test fun cancelledConsentExplainsHowToEnableAlertsAgain() {
        assertTrue(updateFailureMessage(Status.AUTHENTICATION_CANCELED).contains("sign in again"))
    }

    @Test fun disabledApiAndMissingSdkAreActionableFailures() {
        assertTrue(updateFailureMessage(Status.API_DISABLED).contains("disabled"))
        assertTrue(updateFailureMessage(Status.NOT_IMPLEMENTED).contains("does not include"))
        assertTrue(updateFailureMessage(Status.HOST_ACTIVITY_INTERRUPTED).contains("retry"))
    }

    @Test fun onlyNoUpdateResultReportsLatestBuild() {
        assertTrue(updateFailureMessage(Status.UPDATE_NOT_AVAILABLE).contains("latest build"))
        for (code in Status.values().filter { it != Status.UPDATE_NOT_AVAILABLE }) {
            assertTrue(!updateFailureMessage(code).contains("latest build"))
        }
    }
}
