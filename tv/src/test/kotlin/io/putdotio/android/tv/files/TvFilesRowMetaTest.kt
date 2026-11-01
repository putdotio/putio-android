package io.putdotio.android.tv.files

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesRowMetaTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aFolderRowShowsItsSizeAndLastChangeButASharedRootShowsNoSize() {
        val fourDaysAgo = Instant.now().minus(Duration.ofDays(4)).truncatedTo(ChronoUnit.SECONDS).toString()
        compose.showFiles(
            state = {
                ready(
                    item(1, "Series", PutioFileType.FOLDER, sizeBytes = 63_300_000).copy(updatedAt = fourDaysAgo),
                    item(2, "Items shared with you", PutioFileType.FOLDER, sizeBytes = 41_000_000)
                        .copy(updatedAt = fourDaysAgo, folderType = PutioFolderType.SHARED_ROOT),
                )
            },
        )

        fun row(name: String, meta: String) = compose.onAllNodes(
            hasTestTag(TV_FILES_ROW_TAG) and hasAnyDescendant(hasText(name)) and hasAnyDescendant(hasText(meta)),
            useUnmergedTree = true,
        )
        row("Series", "63.3 MB").assertCountEquals(1)
        row("Series", "4 days ago").assertCountEquals(1)
        row("Items shared with you", "4 days ago").assertCountEquals(1)
        compose.onAllNodesWithText("41 MB", substring = true).assertCountEquals(0)
    }
}
