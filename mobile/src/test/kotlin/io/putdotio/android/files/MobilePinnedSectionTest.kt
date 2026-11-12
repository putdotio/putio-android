package io.putdotio.android.files

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h1600dp")
class MobilePinnedSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val pins = (1..6).map { n ->
        FilesItem(FilesItemId(n.toLong()), FilesItemId(40L), "Pin $n", PutioFileType.FOLDER, 0L, "2026-10-13T10:00:00")
    }
    private val opened = mutableListOf<FilesItem>()
    private val unpinned = mutableListOf<FilesItem>()

    @Test
    fun manyPinsShowAFewUntilShowAllRevealsTheRest() {
        setSection()

        compose.onNodeWithText("Pinned").assertExists()
        compose.onNodeWithText("Pin 4").assertExists()
        compose.onNodeWithText("Pin 5").assertDoesNotExist()

        compose.onNodeWithText("Show all").performClick()
        compose.onNodeWithText("Pin 6").assertExists()

        compose.onNodeWithText("Show less").performClick()
        compose.onNodeWithText("Pin 6").assertDoesNotExist()
    }

    @Test
    fun aTapOpensThePinAndItsMenuUnpinsIt() {
        setSection()

        compose.onNodeWithText("Pin 2").performClick()
        compose.runOnIdle { assertEquals(listOf(pins[1]), opened) }

        compose.onNodeWithContentDescription("Actions for Pin 3").performClick()
        compose.onNodeWithText("Unpin").performClick()
        compose.runOnIdle { assertEquals(listOf(pins[2]), unpinned) }
    }

    @Test
    fun theSheetOffersPinOrUnpinByWhetherTheItemIsPinned() {
        val item = pins.first()
        val chosen = mutableListOf<Boolean>()
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    item = item,
                    folderId = FilesItemId(40L),
                    operation = FilesFolderOperation.Idle,
                    renameCompletion = null,
                    onEvent = {},
                    onDismiss = {},
                    itemActions = MobileFilesItemActions(
                        pinnedIds = listOf(item.id),
                        onSetPinned = { _, pinned -> chosen += pinned },
                    ),
                )
            }
        }

        compose.onNodeWithText("Unpin").performScrollTo().assertExists()
        compose.onNodeWithTag(MOBILE_FILES_PIN_ACTION_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(false), chosen) }
    }

    @Test
    fun theSheetLeavesPinOutWhileThePinsAreUnknown() {
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    item = pins.first(),
                    folderId = FilesItemId(40L),
                    operation = FilesFolderOperation.Idle,
                    renameCompletion = null,
                    onEvent = {},
                    onDismiss = {},
                    itemActions = MobileFilesItemActions(pinnedIds = null, onSetPinned = { _, _ -> }),
                )
            }
        }

        compose.onNodeWithTag(MOBILE_FILES_PIN_ACTION_TAG).assertDoesNotExist()
    }

    private fun setSection() {
        compose.setContent {
            PutioTheme {
                LazyColumn {
                    mobileFilesHeaderItems(
                        headers = MobileFilesHeaders(pinned = pins),
                        onPlay = {},
                        pinnedActions = MobilePinnedActions(onOpen = opened::add, onUnpin = unpinned::add),
                    )
                }
            }
        }
    }
}
