package com.focuslock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.auth.AccountScreen
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSignInUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun accountSignInOpensInAppScreenAndContinueOfflineReturnsToAccount() {
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                AccountScreen(
                    authState = FocusAuthState.SignedOut,
                    syncStatus = "Signed out",
                    onSyncNow = {},
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithText("Sign in to sync").assertIsDisplayed().performClick()

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Sign in to FocusLock").assertIsDisplayed()
        compose.onNodeWithText("Continue offline").assertIsDisplayed().performClick()

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Sign in to sync").assertIsDisplayed()
        compose.onNodeWithText("Sign in to FocusLock").assertDoesNotExist()
    }
}
