package io.putdotio.android.tv.files

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesDetailTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theDetailShowsAVeryLongNameUncutAboveItsActions() {
        val name = List(EPISODES) { "Deep.Sea.Creatures.S01E0$it.The.Midnight.Zone" }.joinToString(".") + ".mkv"
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(1, name, PutioFileType.VIDEO)),
                onEvent = { true },
                onPlayMedia = {},
                onPlay = { _, _ -> },
            )
        }

        compose.onNode(isHeading() and hasText(name)).assertHeightIsAtLeast(DETAIL_TITLE_LINE * 6)
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsDisplayed()
    }

    @Test
    fun aSharedFolderNeverAsksForItsCount() {
        var reads = 0
        val counts = FilesFolderCounts { PutioResult.Success(++reads) }
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(-1, "Items shared with you", PutioFileType.FOLDER)),
                onEvent = { true },
                onPlayMedia = {},
                detailSources = remember { TvFilesDetailSources(folderCounts = counts) },
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertEquals(0, reads)
    }

    private companion object {
        val DETAIL_TITLE_LINE = 22.dp
        const val EPISODES = 9
    }
}
