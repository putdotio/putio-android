package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesSort
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesSortTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aFolderWithoutItsOwnSortNamesTheAccountSortInEffect() {
        var accountSort by mutableStateOf<FilesSort?>(FilesSort.DATE_ADDED_DESCENDING)
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(1, "a.txt", PutioFileType.TEXT)),
                onEvent = { true },
                onPlayMedia = {},
                accountDefaultSort = accountSort,
            )
        }
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Date added, newest first") and hasClickAction()).assertIsFocused()
            .performKeyInput { pressCenter() }
        compose.onNode(hasText("Newest first") and hasClickAction() and isSelected()).assertIsDisplayed()

        accountSort = null
        compose.onNode(hasText("Sort") and hasClickAction()).assertIsDisplayed()
    }
}
