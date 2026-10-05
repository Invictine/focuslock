package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.location.HomeLocationStatus
import com.focuslock.app.location.HomePlace
import com.focuslock.app.ui.strict.HomeLocationCard
import com.focuslock.app.ui.strict.StrictLocationPickerDialog
import com.focuslock.app.ui.strict.StrictLocationSelection
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only checks: all persisted values and actions are supplied by the test. */
@RunWith(AndroidJUnit4::class)
class HomeLocationUiTest {
    @get:Rule val compose = createComposeRule()

    private val home = HomePlace("Home", 12.9716, 77.5946, 425f)

    @Test
    fun cardShowsAwayAndUnavailableStatesAndDisablingCallsFalse() {
        val status = mutableStateOf(HomeLocationStatus.AWAY)
        val enabled = mutableStateOf(true)
        val homeOnlyChanges = mutableListOf<Boolean>()
        compose.setContent {
            FocusLockTheme {
                Surface {
                    HomeLocationCard(
                        homePlace = home,
                        enabled = enabled.value,
                        status = status.value,
                        onSaveHome = {},
                        onHomeOnlyChange = { homeOnlyChanges += it },
                        onChooseLocation = { _, _ -> },
                    )
                }
            }
        }

        compose.onNodeWithText("Paused · A fresh location confirms you're away").assertIsDisplayed()
        saveScreenshot("home-location-card")

        compose.runOnIdle { status.value = HomeLocationStatus.UNAVAILABLE }
        compose.onNodeWithText("Active · Waiting for a fresh, accurate location").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(listOf(false), homeOnlyChanges) }
        compose.onNodeWithText("Paused · A fresh location confirms you're away").assertDoesNotExist()
    }

    @Test
    fun editReturnsTheSavedCoordinatesAndRadius() {
        var editSelection: StrictLocationSelection? = null
        var editLabel: String? = null
        compose.setContent {
            FocusLockTheme {
                Surface {
                    HomeLocationCard(
                        homePlace = home,
                        enabled = false,
                        status = HomeLocationStatus.DISABLED,
                        onSaveHome = {},
                        onHomeOnlyChange = {},
                        onChooseLocation = { selection, label -> editSelection = selection; editLabel = label },
                    )
                }
            }
        }

        compose.onNodeWithText("Edit").performClick()
        compose.runOnIdle {
            assertEquals(StrictLocationSelection("Home", 12.9716, 77.5946, 425f), editSelection)
            assertEquals("Home", editLabel)
        }
    }

    @Test
    fun pickerKeepsInitialAddressCoordinatesAndRadiusAndOffersMapPreview() {
        val saved = StrictLocationSelection("14 Example Road, Bengaluru", 12.9716, 77.5946, 425f)
        compose.setContent {
            FocusLockTheme {
                StrictLocationPickerDialog(
                    initialLabel = saved.label,
                    initialRadiusMeters = saved.radiusMeters,
                    initialSelection = saved,
                    onDismiss = {},
                    onSelected = {},
                )
            }
        }

        compose.onNodeWithText("Selected: ${saved.label}").assertIsDisplayed()
        compose.onNodeWithText("12.97160, 77.59460").assertIsDisplayed()
        compose.onNodeWithText("Radius: 425 m").assertIsDisplayed()
        compose.onNodeWithText("Preview map").assertIsDisplayed()
        saveScreenshot("home-location-picker")
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = if (name == "home-location-picker") {
            // Dialogs live in a separate Android window; capture the screen rather
            // than the empty underlying Compose root.
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } else compose.onRoot().captureToImage().asAndroidBitmap()
        context.cacheDir.resolve("$name.png").outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
