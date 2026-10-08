package com.focuslock.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.auth.FocusLockStartupGate
import com.focuslock.app.ui.onboarding.ProductOnboardingDialog
import com.focuslock.app.ui.onboarding.ProductTourDestination
import com.focuslock.app.ui.onboarding.productTourPages
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductOnboardingAuthGateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingAndSignedOutHideGuideUntilSignInThenPreserveUnfinishedPage() {
        val auth = mutableStateOf<FocusAuthState>(FocusAuthState.Loading)
        val offline = mutableStateOf(false)
        val page = mutableIntStateOf(0)
        var paused = false
        var finished = false

        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                startupGate(auth.value, offline.value, { offline.value = true }, page,
                    { paused = true }, { finished = true })
            }
        }

        compose.onNodeWithText("FocusLock guide").assertDoesNotExist()
        auth.value = FocusAuthState.SignedOut
        compose.onNodeWithText("Stay focused everywhere").assertIsDisplayed()
        compose.onNodeWithText("Continue offline").assertIsDisplayed()
        compose.onNodeWithText("FocusLock guide").assertDoesNotExist()

        auth.value = FocusAuthState.SignedIn
        compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, page.intValue)
        auth.value = FocusAuthState.SignedOut
        compose.onNodeWithText("Stay focused everywhere").assertIsDisplayed()
        compose.onNodeWithText("FocusLock guide").assertDoesNotExist()
        assertFalse(paused)
        assertFalse(finished)

        auth.value = FocusAuthState.SignedIn
        compose.onNodeWithText(productTourPages[1].title).assertIsDisplayed()
        assertEquals(1, page.intValue)
        assertFalse(paused)
        assertFalse(finished)
    }

    @Test
    fun continueOfflineFromSignInOpensGuideAndLoadingRetainsExplicitOfflineMode() {
        val auth = mutableStateOf<FocusAuthState>(FocusAuthState.SignedOut)
        val offline = mutableStateOf(false)
        val page = mutableIntStateOf(0)
        var paused = false
        var finished = false

        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                startupGate(auth.value, offline.value, { offline.value = true }, page,
                    { paused = true }, { finished = true })
            }
        }

        compose.onNodeWithText("Stay focused everywhere").assertIsDisplayed()
        compose.onNodeWithText("Continue offline").performClick()
        compose.onNodeWithText("FocusLock guide").assertIsDisplayed()

        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, page.intValue)
        auth.value = FocusAuthState.Loading
        compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
        assertEquals(1, page.intValue)
        assertFalse(paused)
        assertFalse(finished)
    }

    @Test
    fun unconfiguredAuthAllowsGuideAndGateDisposalDoesNotPauseOrFinishIt() {
        val auth = mutableStateOf<FocusAuthState>(FocusAuthState.Unconfigured)
        val offline = mutableStateOf(false)
        val page = mutableIntStateOf(0)
        var paused = false
        var finished = false

        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                startupGate(auth.value, offline.value, {}, page,
                    { paused = true }, { finished = true })
            }
        }

        compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, page.intValue)

        auth.value = FocusAuthState.SignedOut
        compose.onNodeWithText("Stay focused everywhere").assertIsDisplayed()
        compose.onNodeWithText("FocusLock guide").assertDoesNotExist()
        assertFalse(paused)
        assertFalse(finished)

        auth.value = FocusAuthState.Unconfigured
        compose.onNodeWithText(productTourPages[1].title).assertIsDisplayed()
        assertEquals(1, page.intValue)
        assertFalse(paused)
        assertFalse(finished)
    }
}

@Composable
private fun startupGate(
    authState: FocusAuthState,
    offlineMode: Boolean,
    onContinueOffline: () -> Unit,
    page: androidx.compose.runtime.MutableIntState,
    onPause: () -> Unit,
    onFinish: () -> Unit,
) {
    FocusLockStartupGate(authState, offlineMode, onContinueOffline) {
        ProductOnboardingDialog(
            pageIndex = page.intValue,
            onPageChange = { page.intValue = it },
            onPause = onPause,
            onFinish = onFinish,
            onOpenDestination = { _: ProductTourDestination -> },
        )
    }
}
