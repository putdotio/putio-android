package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.assertInsideTvSafeArea
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The player's title, option buttons and seek controls keep clear of the TV overscan edges (#45). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerSafeAreaTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theControlsStayInsideTheSafeArea() {
        showControls()
        assertControlsInsideSafeArea()
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-television")
    fun theSafeAreaScalesWithTheViewport() {
        showControls()
        assertControlsInsideSafeArea()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-television-xxxhdpi")
    fun aFourKPanelKeepsTheSameProportionClear() {
        showControls()
        assertControlsInsideSafeArea()
    }

    private fun showControls() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState(),
                    onBack = {},
                    onRetry = {},
                    onResume = {},
                    onRestart = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> TrackPlayer() },
                )
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    private fun assertControlsInsideSafeArea() {
        val controls = listOf(
            compose.onNodeWithText(TITLE),
            compose.onNodeWithContentDescription("Language"),
            compose.onNodeWithContentDescription("Subtitles"),
            compose.onNodeWithContentDescription("Speed"),
            compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG),
            compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG),
            compose.onNodeWithContentDescription("Playing"),
            compose.onNodeWithText("14:00"),
        ).map { it.fetchSemanticsNode() }
        compose.assertInsideTvSafeArea(controls)
    }

    private fun readyState() = PlaybackState(
        target = PlaybackTarget(FilesItemId(9), TITLE, PlaybackMediaType.VIDEO),
        content = PlaybackContent.Ready(
            PlaybackSource(
                fileId = 9,
                kind = PlaybackSourceKind.HLS,
                url = PutioCredentialUrl::class.java
                    .getDeclaredConstructor(String::class.java)
                    .newInstance("https://api.put.io/v2/files/9/hls/media.m3u8?token=t"),
                startFromSeconds = 0.0,
                subtitles = PlaybackSubtitles.None,
            ),
            useStartFrom = false,
        ),
        nextRequestValue = 2L,
        resumePositionMillis = null,
    )

    private companion object {
        const val SETTLE_MILLIS = 50L
        const val TITLE = "Sintel.mp4"
    }
}
