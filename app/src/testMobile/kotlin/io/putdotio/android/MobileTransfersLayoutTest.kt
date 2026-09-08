package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransfersRequestId
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
    fun largeFontToolbarPreservesTheContentViewportAndRevealsSecondaryActions() {
        setScreen()
        assertToolbar(2f)
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Clean completed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Transfer actions").performClick()
        assertUnclippedText("Refresh", 2f)
        assertUnclippedText("Clean completed", 2f)
        compose.onNodeWithText("Clean completed").performClick()
        compose.onNodeWithText("Clean completed transfers?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun normalTextKeepsCompactToolbarAndTrailingRowActions() {
        setScreen(fontScale = 1f)
        assertToolbar(1f)
        val name = compose.onNodeWithText(TRANSFER_NAME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val open = compose.onNodeWithContentDescription("Open $TRANSFER_NAME").fetchSemanticsNode().boundsInRoot
        assertTrue("Normal text keeps the action beside the name", open.left >= name.right)
    }

    @Test
    fun largeTextRowGivesMetadataTheFullWidthAndKeepsBothActionsBelowIt() {
        val events = mutableListOf<TransfersEvent>()
        setScreen(onEvent = events::add)
        val name = compose.onNodeWithText(TRANSFER_NAME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("Seeding", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val open = compose.onNodeWithContentDescription("Open $TRANSFER_NAME").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithContentDescription("Cancel transfer $TRANSFER_NAME")
            .fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("Metadata uses the row width", name.width > list.width * 0.8f)
        assertTrue("Open follows metadata", open.top >= status.bottom)
        assertTrue("Cancel follows metadata", cancel.top >= status.bottom)
        assertFalse("Action targets must not overlap", open.overlaps(cancel))
        compose.onNodeWithContentDescription("Open $TRANSFER_NAME").assertIsDisplayed().performClick()
        assertEquals(listOf(TransfersEvent.Open(TransferId(1))), events)
        compose.onNodeWithContentDescription("Cancel transfer $TRANSFER_NAME").performClick()
        compose.onNodeWithText("Stop transfer").performClick()
        assertEquals(TransfersEvent.Cancel(TransferId(1)), events.last())
    }

    @Test
    fun largeTextOverflowRetainsDisabledActionsDuringMutation() {
        setScreen(mutation = TransferMutation.Running(TransferAction.Cancel(TransferId(1)), TransfersRequestId(1)))
        compose.onNodeWithText("Add transfer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open $TRANSFER_NAME").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cancel transfer $TRANSFER_NAME").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Transfer actions").assertIsEnabled().performClick()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithText("Clean completed").assertIsNotEnabled()
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
    private fun setScreen(
        fontScale: Float = 2f,
        mutation: TransferMutation = TransferMutation.Idle,
        onEvent: (TransfersEvent) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PutioTheme {
                    Box(modifier = Modifier.fillMaxSize().testTag("transfers-viewport")) {
                        MobileTransfersScreen(
                            TransfersState(
                                content = TransfersContent.Ready(listOf(TransferItem(
                                    id = TransferId(1), name = TRANSFER_NAME, status = AppTransferStatus.Seeding,
                                    fileId = TransferFileId(2), sizeBytes = null, percentDone = null,
                                    downloadSpeedBytesPerSecond = null, uploadSpeedBytesPerSecond = null,
                                    estimatedSecondsRemaining = null, availability = null, hasError = false,
                                    createdAt = "2026-09-08T12:00:00Z", userFileExists = true,
                                )), TransfersPaging.Complete),
                                mutation = mutation,
                            ),
                            onEvent = onEvent,
                        )
                    }
                }
            }
        }
    }

    private fun assertToolbar(fontScale: Float) {
        assertUnclippedText("Add transfer", fontScale)
        val viewport = compose.onNodeWithTag("transfers-viewport").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("Content keeps at least 80% of the screen", list.height >= viewport.height * 0.8f)
        val add = compose.onNodeWithText("Add transfer").fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithContentDescription("Transfer actions").fetchSemanticsNode().boundsInRoot
        assertTrue("Toolbar fits on one row", add.bottom > menu.top && menu.bottom > add.top)
        assertTrue("Overflow never crowds the Add action", add.right <= menu.left)
    }

    private fun assertUnclippedText(label: String, fontScale: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(fontScale, layout.layoutInput.density.fontScale)
        for (line in 0 until layout.lineCount) {
            assertFalse("$label must not be ellipsized", layout.isLineEllipsized(line))
            assertTrue("$label line must fit horizontally", layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("$label line must fit vertically", layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private companion object {
        const val TRANSFER_NAME = "Concert recording.mp4"
    }

}
