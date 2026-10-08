package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.auth.AccountScreen
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.auth.NativeSignInScreen
import com.focuslock.app.auth.NativeSignInUiState
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccountSignInUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun accountEmailActionOpensFullScreenRouteAndBackReturnsToAccount() {
        var showSignIn by mutableStateOf(false)
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                if (showSignIn) {
                    NativeSignInScreen(
                        state = NativeSignInUiState(),
                        onGoogle = {}, onShowEmail = {}, onEmail = {}, onPassword = {},
                        onCode = {}, onProfile = {}, onResendCode = {},
                        onChooseSecondFactor = {}, onBackToOptions = {},
                        onBack = { showSignIn = false },
                    )
                } else {
                    AccountScreen(
                        authState = FocusAuthState.SignedOut,
                        syncStatus = "Signed out",
                        onSyncNow = {},
                        onSignOut = {},
                        onOpenSignIn = { showSignIn = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Use email instead").assertIsDisplayed().performClick()

        compose.onNodeWithText("Stay focused everywhere").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Continue offline").assertDoesNotExist()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "native-sign-in.png")
        screenshot.outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        compose.onNodeWithText("Back").performClick()

        compose.onNodeWithText("Sync across your devices").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
    }

    @Test
    fun explicitGoogleActionInvokesOnlyItsNativeCallbackAndShowsInlineFailure() {
        var attempts = 0
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                NativeSignInScreen(
                    state = NativeSignInUiState(error = "No Google account is available on this device."),
                    onGoogle = { attempts++ }, onShowEmail = {}, onEmail = {}, onPassword = {},
                    onCode = {}, onProfile = {}, onResendCode = {},
                    onChooseSecondFactor = {}, onBackToOptions = {}, onContinueOffline = {},
                )
            }
        }

        compose.onNodeWithText("No Google account is available on this device.").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").performClick()
        assertEquals(1, attempts)
    }

    @Test
    fun signedOutAccountStartsGoogleDirectlyAndShowsCancellationWithoutAnOverlay() {
        var attempts = 0
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                AccountScreen(
                    authState = FocusAuthState.SignedOut,
                    syncStatus = "Signed out",
                    onSyncNow = {},
                    onSignOut = {},
                    onGoogleSignIn = { attempts++ },
                    googleSignInError = null,
                )
            }
        }

        compose.onNodeWithText("Continue with Google").assertIsDisplayed().performClick()
        assertEquals(1, attempts)
        compose.onNodeWithText("Sync across your devices").assertIsDisplayed()
        compose.onNodeWithText("Stay focused everywhere").assertDoesNotExist()
    }
}
