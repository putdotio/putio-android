package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port")
class MobileTransfersLayoutTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(2f) }
    }).around(compose)

    @Test
    fun largeFontToolbarWrapsWithoutClippingActions() {
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    TransfersState(content = TransfersContent.Ready(listOf(TransferItem(
                        id = TransferId(1), name = "Concert.mp4", status = AppTransferStatus.Completed,
                        fileId = null, sizeBytes = null, percentDone = null,
                        downloadSpeedBytesPerSecond = null, uploadSpeedBytesPerSecond = null,
                        estimatedSecondsRemaining = null, availability = null, hasError = false,
                        createdAt = "2026-09-08T12:00:00Z", userFileExists = null,
                    )), TransfersPaging.Complete)),
                    onEvent = {},
                )
            }
        }

        listOf("Refresh", "Clean completed", "Add transfer").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(2f, layouts.single().layoutInput.density.fontScale)
            val layout = layouts.single()
            for (line in 0 until layout.lineCount) {
                assertFalse("$label must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue("$label line must fit horizontally", layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("$label line must fit vertically", layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w640dp-h320dp-land")
    fun shortLargeFontSheetCanScrollFromInputToSubmit() {
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(TransfersState(content = TransfersContent.Empty), events::add) }
        }
        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performScrollTo()
            .performTextInput("https://example.com/concert.mp4")
        compose.onNodeWithText("Add").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(TransfersEvent.Add("https://example.com/concert.mp4")), events)
    }
}
