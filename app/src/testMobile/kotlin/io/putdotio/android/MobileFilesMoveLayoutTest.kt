package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationEvent
import io.putdotio.android.files.FilesMoveDestinationFolder
import io.putdotio.android.files.FilesMoveDestinationState
import io.putdotio.android.files.FilesPaging
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w640dp-h320dp-land")
class MobileFilesMoveLayoutTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(2f) }
    }).around(compose)

    @Test
    fun shortLandscapeAtLargeFontKeepsActionsVisibleAndDestinationsScrollable() = assertLargeFontLayout()

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port")
    fun portraitAtLargeFontKeepsActionsVisibleAndDestinationsScrollable() = assertLargeFontLayout()

    private fun assertLargeFontLayout() {
        val destination = FilesFolder(FilesItemId(8), "Destination 東京 été ".repeat(16).take(240))
        val folders = (20L..34L).map { id ->
            FilesItem(FilesItemId(id), destination.id, "Folder été $id", PutioFileType.FOLDER, 0, "2026-09-06")
        }
        val state = FilesMoveDestinationState(
            sourceItem = FilesItem(FilesItemId(7), FilesFolder.Root.id,
                "Archive 東京 été ".repeat(16).take(240), PutioFileType.FOLDER, 0, "2026-09-06"),
            sourceFolderId = FilesFolder.Root.id,
            stack = listOf(
                FilesMoveDestinationFolder(FilesFolder.Root, FilesContent.Empty(FilesPaging.Complete)),
                FilesMoveDestinationFolder(destination,
                    FilesContent.Ready(folders, FilesPaging.Available(FilesCursor("next-page")))),
            ),
            nextRequestValue = 1,
        )
        val events = mutableListOf<FilesMoveDestinationEvent>()
        var cancellations = 0
        var confirmations = 0
        compose.setContent {
            PutioTheme {
                MobileFilesMoveDestination(state, events::add,
                    onCancel = { cancellations += 1 }, onConfirm = { confirmations += 1 })
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(state.sourceItem.name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertEquals("The dialog must actually render at 200% font scale", 2f, layouts.single().layoutInput.density.fontScale)
        assertActionFitsPicker(MOBILE_FILES_MOVE_CANCEL_TAG)
        assertActionFitsPicker(MOBILE_FILES_MOVE_HERE_TAG)
        val list = compose.onNodeWithTag(MOBILE_FILES_MOVE_LIST_TAG)
        val listBounds = list.getUnclippedBoundsInRoot()
        assertTrue("Destination list must have a positive viewport: $listBounds", listBounds.bottom > listBounds.top)
        list.performScrollToNode(hasTestTag(mobileFilesMoveFolderTag(folders.last().id)))
        val lastFolder = compose.onNodeWithTag(mobileFilesMoveFolderTag(folders.last().id))
        val folderBounds = lastFolder.getUnclippedBoundsInRoot()
        assertTrue("Scrolled destination must fit its viewport: folder=$folderBounds, list=$listBounds",
            folderBounds.top >= listBounds.top && folderBounds.bottom <= listBounds.bottom)
        lastFolder.assertIsDisplayed().performClick()
        list.performScrollToNode(hasTestTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG))
        compose.onNodeWithTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG).assertIsDisplayed().performClick()
        assertActionFitsPicker(MOBILE_FILES_MOVE_CANCEL_TAG)
        assertActionFitsPicker(MOBILE_FILES_MOVE_HERE_TAG)
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf(FilesMoveDestinationEvent.OpenFolder(folders.last().id),
                FilesMoveDestinationEvent.LoadNextPage), events)
            assertEquals(1, cancellations)
            assertEquals(1, confirmations)
        }
    }

    private fun assertActionFitsPicker(tag: String) {
        val picker = compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag(tag)
        val bounds = action.getUnclippedBoundsInRoot()
        assertTrue("$tag must have visible content: action=$bounds, picker=$picker", bounds.bottom > bounds.top)
        assertTrue("$tag must fit inside the picker: action=$bounds, picker=$picker",
            bounds.top >= picker.top && bounds.bottom <= picker.bottom &&
                bounds.left >= picker.left && bounds.right <= picker.right)
        action.assertIsDisplayed().assertIsEnabled()
    }
}
