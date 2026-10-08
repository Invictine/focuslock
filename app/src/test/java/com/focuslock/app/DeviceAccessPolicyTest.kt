package com.focuslock.app

import com.focuslock.app.service.DeviceAccessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessPolicyTest {
    private val ownPackage = "com.focuslock.app"

    @Test
    fun registeredSamsungAndInactiveGoogleKeyboardsAreExempt() {
        val registered = setOf("com.samsung.android.honeyboard", "com.google.android.inputmethod.latin")
        assertTrue(DeviceAccessPolicy.isExempt("com.samsung.android.honeyboard", ownPackage, registered))
        // All installed/registered IMEs stay available, even when a different one is currently selected.
        assertTrue(DeviceAccessPolicy.isExempt("com.google.android.inputmethod.latin", ownPackage, registered))
    }

    @Test
    fun coreSettingsSystemUiAndPermissionControllerAreExempt() {
        listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.emergency",
            "com.android.phone",
        ).forEach { assertTrue("$it should remain accessible", DeviceAccessPolicy.isExempt(it, ownPackage, emptySet())) }
    }

    @Test
    fun spoofedAndUnknownSystemLikePackagesAreDenied() {
        assertFalse(DeviceAccessPolicy.isExempt("com.android.settings.malware", ownPackage, emptySet()))
        assertFalse(DeviceAccessPolicy.isExempt("com.android.systemui.fake", ownPackage, emptySet()))
        assertFalse(DeviceAccessPolicy.isExempt("com.example.keyboard", ownPackage, emptySet()))
    }

    @Test
    fun playStoreAndBrowsersAreNotDeviceExemptions() {
        assertFalse(DeviceAccessPolicy.isExempt("com.android.vending", ownPackage, emptySet()))
        assertFalse(DeviceAccessPolicy.isExempt("com.google.android.chrome", ownPackage, emptySet()))
        assertFalse(DeviceAccessPolicy.isExempt("org.mozilla.firefox", ownPackage, emptySet()))
    }

    @Test
    fun emptyOrMissingResolversDoNotCreateWildcardExemption() {
        assertFalse(DeviceAccessPolicy.isExempt("com.example.app", "", emptySet()))
        assertFalse(DeviceAccessPolicy.isExempt("", "", emptySet()))
    }
}
