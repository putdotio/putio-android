package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.filesBrowserState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesCrumbTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aFolderOpenedFromSearchOrAHomePinNamesThatPaneInItsCrumbNotTheFolderBelowIt() {
        val root = ready(item(2, "Movies", PutioFileType.FOLDER))
        val movies = FilesFolderState(
            folder = FilesFolder(FilesItemId(2), "Movies"),
            content = FilesContent.Ready(listOf(item(3, "trailer.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
        )
        val anime = FilesFolderState(
            folder = FilesFolder(FilesItemId(9), "Anime"),
            content = FilesContent.Ready(listOf(item(10, "pilot.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
        )
        var origin by mutableStateOf(FilesOpenOrigin.SEARCH)
        compose.showFiles(
            state = {
                filesBrowserState(root.stack + movies + anime.copy(openedFrom = origin), nextRequestValue = 11L)
            },
        )

        compose.onNodeWithText("Anime").assertIsDisplayed()
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Movies").assertDoesNotExist()

        origin = FilesOpenOrigin.HOME
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onAllNodesWithText("Your files").assertCountEquals(0)
    }
}
