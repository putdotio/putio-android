package io.putdotio.android.tv.files

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
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
        val name = "The.Very.Long.Release.Name.Of.A.Documentary.Series.About.Deep.Sea.Creatures." +
            "Season.01.Episode.07.The.Midnight.Zone.And.What.Lives.There.2160p.WEB-DL.DDP5.1.Atmos." +
            "DV.HDR10Plus.HEVC-SOMEGROUP.Director.Cut.Extended.Edition.mkv"
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

    private companion object {
        val DETAIL_TITLE_LINE = 22.dp
    }
}
