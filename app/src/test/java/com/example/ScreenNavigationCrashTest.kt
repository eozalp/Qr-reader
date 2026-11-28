package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenNavigationCrashTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchAcrossAllRemoteTabsSettingsTabsAndCameraWithoutCrashing() {
        composeRule.waitForIdle()

        // Interact with touchpad first, then immediately switch tabs
        composeRule.onNodeWithTag("remote_touchpad_surface").performTouchInput {
            swipeRight()
        }
        composeRule.waitForIdle()

        // Switch Kumanda -> Klavye -> 123 -> Oyun -> Kumanda
        composeRule.onNodeWithTag("remote_tab_keyboard").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("remote_tab_numpad").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("remote_tab_gamepad").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("remote_tab_kumanda").performClick()
        composeRule.waitForIdle()

        // Rotate mode via dock button + swipe gesture
        composeRule.onNodeWithTag("remote_rotate_mode_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_swipe_mode_dock").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // Enter OLED AOD mode, touch pad, and exit
        composeRule.onNodeWithTag("remote_oled_aod_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("oled_aod_touchpad").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("oled_aod_exit_btn").performClick()
        composeRule.waitForIdle()

        // Open Settings and exercise EVERY tab (including Expert tabs + Debug Console)
        composeRule.onNodeWithTag("remote_settings_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_profiles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_overlay").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_keyboard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_expert_toggle_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_table").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_tracking").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_debug").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()

        // Open QR Scanner and return back to Remote
        composeRule.onNodeWithTag("remote_qr_scanner_btn").performClick()
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        // Switch to Klavye again after returning from QR
        composeRule.onNodeWithTag("remote_tab_keyboard").performClick()
        composeRule.waitForIdle()
    }
}
