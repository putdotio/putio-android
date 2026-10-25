package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.MOBILE_TRANSFERS_ADD_FAB_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFERS_LIST_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_CONFIRM_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_FIELD_TAG
import io.putdotio.android.transfers.MobileTransfersOverflow
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.mobileTransferCardTag
import io.putdotio.android.transfers.transfersState
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
    fun largeFontKeepsTheListViewportAndAnUnclippedFab() {
        setScreen()
        assertUnclippedText("Add transfer", 2f)
        val viewport = compose.onNodeWithTag("transfers-viewport").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("Content keeps the whole viewport", list.height >= viewport.height * 0.95f)
        val fab = compose.onNodeWithTag(MOBILE_TRANSFERS_ADD_FAB_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("The FAB stays inside the viewport", fab.right <= viewport.right && fab.bottom <= viewport.bottom)
    }

    @Test
    fun largeFontOverflowKeepsItsActionsWhole() {
        compose.setContent {
            PutioTheme {
                MobileTransfersOverflow(
                    transfersState(
                        TransfersContent.Ready(
                            listOf(transferRow(1L, AppTransferStatus.Completed)),
                            TransfersPaging.Complete,
                        ),
                    ),
                    onEvent = {},
                    sessionId = null,
                )
            }
        }
        compose.onNodeWithContentDescription("More options").performClick()
        assertUnclippedText("Refresh", 2f)
        assertUnclippedText("Clear completed", 2f)
    }

    @Test
    fun largeFontCardKeepsItsNameOnOneLineAndItsRetryBesideIt() {
        val events = mutableListOf<TransfersEvent>()
        setScreen(onEvent = events::add)
        val card = compose.onNodeWithTag(mobileTransferCardTag(TransferId(1))).fetchSemanticsNode().boundsInRoot
        val retryNode = compose.onNodeWithContentDescription("Retry transfer $TRANSFER_NAME").fetchSemanticsNode()
        val retry = retryNode.boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(TRANSFER_NAME, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue("Retry sits inside the card", retry.left >= card.left && retry.right <= card.right)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertTrue("Retry keeps a 48dp target", retryNode.touchBoundsInRoot.width >= 48f * density - 1f)
        compose.onNodeWithContentDescription("Retry transfer $TRANSFER_NAME").performClick()
        assertEquals(listOf<TransfersEvent>(TransfersEvent.RetryTransfer(TransferId(1))), events)
    }

    @Test
    fun normalTextKeepsTheRetryBesideTheName() {
        setScreen(fontScale = 1f)
        val name = compose.onNodeWithText(TRANSFER_NAME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithContentDescription("Retry transfer $TRANSFER_NAME")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Normal text keeps the action beside the name", retry.left >= name.right)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w640dp-h320dp-land")
    fun shortLargeFontSheetCanScrollFromInputToSubmit() {
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(transfersState(content = TransfersContent.Empty), events::add) }
        }
        compose.onNodeWithText("Add transfer").performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performScrollTo()
            .performTextInput("https://example.com/concert.mp4")
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_CONFIRM_TAG).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(TransfersEvent.Add("https://example.com/concert.mp4")), events)
    }

    private fun setScreen(
        fontScale: Float = 2f,
        onEvent: (TransfersEvent) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PutioTheme {
                    Box(modifier = Modifier.fillMaxSize().testTag("transfers-viewport")) {
                        MobileTransfersScreen(
                            transfersState(
                                content = TransfersContent.Ready(
                                    listOf(transferRow(1L, AppTransferStatus.Failed).copy(name = TRANSFER_NAME)),
                                    TransfersPaging.Complete,
                                ),
                            ),
                            onEvent = onEvent,
                        )
                    }
                }
            }
        }
    }

    private fun assertUnclippedText(label: String, fontScale: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        // Material clears the extended FAB's label from the merged tree.
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
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
