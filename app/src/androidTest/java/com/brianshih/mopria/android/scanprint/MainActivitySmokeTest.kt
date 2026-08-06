package com.brianshih.mopria.android.scanprint

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: the app launches and renders the home screen's primary scan action without crashing.
 * Guards against activity/theme/navigation regressions that unit tests cannot see.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsScanAction() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.home_scan_document))
            .assertIsDisplayed()
    }
}
