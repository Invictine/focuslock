package com.focuslock.app

import com.focuslock.app.service.AppUpdateAccessPolicy
import com.focuslock.app.service.FrogAppPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateAccessPolicyTest {
    @Test fun appTesterIsAvailableBeforeAndAfterFrogToolConfirmation() {
        for (confirmed in listOf(false, true)) {
            assertFalse(FrogAppPolicy.shouldBlock(
                AppUpdateAccessPolicy.APP_TESTER_PACKAGE, true, confirmed, emptySet(), emptySet(),
            ))
            assertTrue(FrogAppPolicy.shouldBlock("com.android.chrome", true, confirmed, emptySet(), emptySet()))
        }
    }

    @Test fun updateExemptionMatchesOnlyTheVerifiedPackage() {
        assertTrue(AppUpdateAccessPolicy.isUpdateApp("dev.firebase.appdistribution"))
        for (other in listOf(null, "", "com.google.firebase.appdistribution", "com.android.vending",
            "dev.firebase.appdistribution.fake", "dev.firebase.appdistribution.beta")) {
            assertFalse(AppUpdateAccessPolicy.isUpdateApp(other))
        }
    }
}
