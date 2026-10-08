package com.focuslock.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.service.DeviceAccessPolicy
import com.focuslock.app.service.FrogAppPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the actual Android IME registry rather than a hardcoded keyboard allowlist. */
@RunWith(AndroidJUnit4::class)
class DeviceAccessPlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyRegisteredKeyboardKeepsItsSettingsAndRecoveryAccess() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboards = manager.inputMethodList.map { it.packageName }.toSet()
        assertTrue("The device must have an installed keyboard", keyboards.isNotEmpty())
        keyboards.forEach { packageName ->
            assertTrue(DeviceAccessPolicy.isExempt(context, packageName))
            assertTrue(FrogAppPolicy.isSafetyEssential(context, packageName))
            assertTrue(PermanentBlocksRepository.isProtectedPackage(context, packageName))
        }
    }

    @Test
    fun settingsAndSystemControlsStayProtectedWithoutExemptingBrowsersOrStores() {
        listOf("com.android.settings", "com.android.systemui", "android").forEach {
            assertTrue(DeviceAccessPolicy.isExempt(context, it))
            assertTrue(FrogAppPolicy.isSafetyEssential(context, it))
            assertTrue(PermanentBlocksRepository.isProtectedPackage(context, it))
        }
        listOf("com.android.chrome", "com.sec.android.app.sbrowser", "com.android.vending", "com.example.settings").forEach {
            assertFalse(DeviceAccessPolicy.isExempt(context, it))
        }
    }
}
