package io.putdotio.android.files

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileContinueWatchingRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val entry = ContinueWatchingEntry(FilesItemId(7L), "Sintel.mkv", 600.0, 3_000.0, watchedAt = 1L)
    private val played = mutableListOf<ContinueWatchingEntry>()
    private val removed = mutableListOf<ContinueWatchingEntry>()

    @Test
    fun aLongPressOffersRemoveAndATapStillPlays() {
        setRow()

        compose.onNodeWithContentDescription("Resume Sintel.mkv", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Remove from Continue watching").performClick()
        compose.runOnIdle { assertEquals(listOf(entry), removed) }

        compose.onNodeWithContentDescription("Resume Sintel.mkv", substring = true).performClick()
        compose.runOnIdle { assertEquals(listOf(entry), played) }
    }

    @Test
    fun talkBackReachesRemoveAsAnAction() {
        setRow()

        val card = compose.onNodeWithContentDescription("Resume Sintel.mkv", substring = true).fetchSemanticsNode()
        val remove = card.config[SemanticsActions.CustomActions].single { it.label == "Remove from Continue watching" }
        compose.runOnIdle { remove.action() }

        assertEquals(listOf(entry), removed)
    }

    private fun setRow() {
        compose.setContent {
            PutioTheme {
                LazyColumn {
                    val headers = MobileFilesHeaders(continueWatching = listOf(entry))
                    mobileFilesHeaderItems(headers, played::add, removed::add)
                }
            }
        }
    }
}
