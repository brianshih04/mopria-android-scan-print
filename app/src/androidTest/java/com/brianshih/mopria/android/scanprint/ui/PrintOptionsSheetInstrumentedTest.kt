package com.brianshih.mopria.android.scanprint.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brianshih.mopria.android.scanprint.MainActivity
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DirectIppPrintPrompt
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.IntegrationDevice
import com.brianshih.mopria.android.scanprint.domain.DeviceKind
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.PrintCapabilities
import com.brianshih.mopria.android.scanprint.ui.theme.MopriaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrintOptionsSheetInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun actionsRemainAboveSystemNavigationAreaWithFullCapabilities() {
        val prompt = DirectIppPrintPrompt(
            document = MopriaDocument("doc", "Document", listOf(DocumentPage("page", 1, "Page")), "test"),
            printer = IntegrationDevice("printer", "Printer", DeviceKind.Printer, "IPP"),
            capabilities = PrintCapabilities(
                copies = 1..10,
                colorModes = listOf("color", "monochrome"),
                sides = listOf("one-sided", "two-sided-long-edge"),
                qualities = listOf("draft", "normal", "high"),
                media = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
                orientations = listOf("portrait", "landscape"),
            ),
        )
        composeRule.activity.setContent {
            MopriaTheme { PrintOptionsSheet(prompt, onConfirm = {}, onDismiss = {}) }
        }
        composeRule.waitForIdle()

        val printBounds = composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.documents_print))
            .fetchSemanticsNode().boundsInWindow
        val rootBottom = composeRule.activity.resources.displayMetrics.heightPixels.toFloat()
        val navigationBarHeight = composeRule.activity.resources.getIdentifier(
            "navigation_bar_height",
            "dimen",
            "android",
        ).takeIf { it != 0 }?.let(composeRule.activity.resources::getDimensionPixelSize) ?: 0

        assertTrue(
            "printBottom=${printBounds.bottom}, rootBottom=$rootBottom, navigationBarHeight=$navigationBarHeight",
            printBounds.bottom <= rootBottom - navigationBarHeight,
        )
    }
}
