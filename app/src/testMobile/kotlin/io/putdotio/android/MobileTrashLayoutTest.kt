package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "en-rUS-w640dp-h320dp-land")
class MobileTrashLayoutTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(2f) }
    }).around(compose)

    @Test
    fun landscapeConfirmationKeepsBothActionsAtRealTwoHundredPercentFont() = assertConfirmationFits()

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h640dp-port")
    fun narrowPortraitConfirmationKeepsBothActionsAtRealTwoHundredPercentFont() = assertConfirmationFits()

    private fun assertConfirmationFits() {
        val item = TrashItem(FilesItemId(7), FilesItemId(8), "Archive 東京 été ".repeat(16), PutioFileType.FOLDER, 0)
        val events = mutableListOf<TrashEvent>()
        compose.setContent {
            PutioTheme {
                MobileTrashScreen(TrashState(content = TrashContent.Loaded(emptyList(), null, 0, 0),
                    confirmation = item, confirmationId = 1), events::add)
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(item.name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale)
        val confirm = compose.onNodeWithTag(MOBILE_TRASH_CONFIRM_TAG)
        val cancel = compose.onNodeWithText("Cancel")
        listOf(confirm, cancel).forEach { action ->
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue("Action has visible content: $bounds", bounds.bottom > bounds.top)
            action.assertIsDisplayed().assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(listOf(TrashEvent.ConfirmRestore(1), TrashEvent.CancelRestore), events) }
    }
}
