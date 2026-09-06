package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreOutcome
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.android.trash.TrashState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileTrashScreenTest {
    @get:Rule val compose = createComposeRule()
    private val item = TrashItem(FilesItemId(7), FilesItemId(8), "été 東京.txt", PutioFileType.TEXT, 12,
        "2026-09-06T00:00:00Z", "2026-09-20")
    private val loaded = TrashContent.Loaded(listOf(item), null, 1, 12)

    @Test
    fun queuedAndUnknownResultsOfferOnlyReadRecoveryAndDisableTheStaleRow() {
        val events = mutableListOf<TrashEvent>()
        var state by mutableStateOf(TrashState(content = loaded, restoreOutcome = TrashRestoreOutcome(item,
            TrashRestoreSubmission.ACKNOWLEDGED, TrashRestoreCheck.UNAVAILABLE)))
        compose.setContent { PutioTheme { MobileTrashScreen(state, events::add) } }
        compose.onNodeWithText("Restore started.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_CHECK_TAG).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(item.name))
        compose.onNodeWithContentDescription("Restore ${item.name}").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(restoreOutcome = state.restoreOutcome?.copy(submission = TrashRestoreSubmission.UNCERTAIN))
        }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText("Check status"))
        compose.onNodeWithTag(MOBILE_TRASH_CHECK_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(TrashEvent.CheckRestore, TrashEvent.CheckRestore), events) }
    }

    @Test
    fun authoritativeRenamedItemRemainsAvailableWhenTrashRefreshFails() {
        val failure = FilesFailure.NetworkUnavailable(PutioConfigurationException("Synthetic read failure"))
        val current = FilesItem(item.id, FilesItemId(0), "été 東京 (1).txt", PutioFileType.TEXT, 12, "2026-09-06")
        val state = TrashState(content = loaded.copy(refreshFailure = failure),
            restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.ACKNOWLEDGED,
                TrashRestoreCheck.AVAILABLE, resolvedItem = current), restoredItemIds = setOf(item.id))
        val events = mutableListOf<TrashEvent>()
        compose.setContent { PutioTheme { MobileTrashScreen(state, events::add) } }
        compose.onNodeWithText("Available again: ${current.name}").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_CHECK_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText("Try again"))
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(listOf(TrashEvent.Refresh), events) }
    }

    @Test
    fun emptyIntermediatePageOffersContinuationWithoutClaimingEmptyTrash() {
        val state = TrashState(content = TrashContent.Loaded(emptyList(), FilesCursor("next"), 7, 1234))
        compose.setContent { PutioTheme { MobileTrashScreen(state, { true }) } }
        compose.onNodeWithText("Trash is empty").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertIsDisplayed()
        compose.onNodeWithText("Deleted items will appear here when Trash is enabled.").assertDoesNotExist()
    }

    @Test
    fun missingAndInvalidExpirationNeverProduceADeadline() {
        val context = RuntimeEnvironment.getApplication()
        assertNull("not-a-date".trashDisplayDate(context))
        assertNull("2026-99-99".trashDisplayDate(context))
        val state = TrashState(content = loaded.copy(items = listOf(item.copy(expirationDate = "invalid", deletedAt = null))))
        compose.setContent { PutioTheme { MobileTrashScreen(state, { true }) } }
        compose.onNodeWithText("Expires", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Deleted", substring = true).assertDoesNotExist()
    }

    @Test
    fun backendNaiveUtcDatesDisplayTheSameDatesAsExplicitUtc() {
        val context = RuntimeEnvironment.getApplication()
        val deleted = "2026-09-06T10:00:00"
        val expires = "2026-09-20T10:00:00"
        assertEquals("${deleted}Z".trashDisplayDate(context), deleted.trashDisplayDate(context))
        val state = TrashState(content = loaded.copy(items = listOf(item.copy(
            deletedAt = deleted, expirationDate = expires))))
        compose.setContent { PutioTheme { MobileTrashScreen(state, { true }) } }
        compose.onNodeWithText(context.getString(R.string.mobile_trash_deleted,
            "${deleted}Z".trashDisplayDate(context))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mobile_trash_expires,
            "${expires}Z".trashDisplayDate(context))).assertIsDisplayed()
    }
}
