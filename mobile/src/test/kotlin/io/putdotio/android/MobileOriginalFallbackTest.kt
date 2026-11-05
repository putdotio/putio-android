package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackReducer
import io.putdotio.android.playback.PlaybackRequestId
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.playbackState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileOriginalFallbackTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ready(PlaybackSourceKind.ORIGINAL))
    private val players = mutableListOf<RecordingPlayer>()
    private val failures = mutableListOf<PlaybackFailure>()

    @Test
    fun tracksTheDeviceCannotDecodeHandOverToConvertedAtTheSamePosition() {
        setScreen()
        compose.runOnIdle {
            players.single().movePositionTo(42_000L)
            players.single().updateTracks(UnsupportedVideo)
        }
        compose.runOnIdle {
            assertEquals(PlaybackContent.Loading(PlaybackRequestId(2L)), state.content)
            assertTrue(state.resumePositionMillis in SamePosition)
            state = PlaybackReducer.reduce(
                state,
                PlaybackEvent.ResolveSucceeded(
                    PlaybackRequestId(2L),
                    PlaybackResolution.Ready(source(PlaybackSourceKind.HLS)),
                ),
            ).state
        }
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertTrue(players.last().currentPosition in SamePosition)
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun aParsingFailureBeforeTheFirstFrameFallsBack() {
        setScreen()
        compose.runOnIdle {
            players.single().fail(
                PlaybackException("synthetic", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
            )
        }
        compose.runOnIdle {
            assertTrue(state.content is PlaybackContent.Loading)
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun aDecoderFailureAfterTheFirstFrameFails() {
        setScreen()
        compose.runOnIdle { players.single().renderFirstFrame() }
        compose.runOnIdle { players.single().fail(DecoderFailure) }
        compose.runOnIdle {
            assertTrue(state.content is PlaybackContent.Ready)
            assertEquals(1, failures.size)
        }
    }

    @Test
    fun aConvertedStreamThatFailsEarlyStillFails() {
        state = ready(PlaybackSourceKind.HLS)
        setScreen()
        compose.runOnIdle { players.single().fail(DecoderFailure) }
        compose.runOnIdle {
            assertTrue(state.content is PlaybackContent.Ready)
            assertEquals(1, failures.size)
        }
    }

    private fun setScreen() {
        compose.setContent {
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { failure, _ -> failures += failure },
                    onOriginalRejected = { reason, positionMillis ->
                        val event = PlaybackEvent.OriginalRejected(reason, positionMillis)
                        PlaybackReducer.reduce(state, event).also { state = it.state }.consumed
                    },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { _, _ -> RecordingPlayer().also(players::add) },
                )
            }
        }
    }

    private fun ready(kind: PlaybackSourceKind): PlaybackState =
        playbackState(target = Video, content = PlaybackContent.Ready(source(kind)), nextRequestValue = 2L)

    private fun source(kind: PlaybackSourceKind): PlaybackSource =
        PlaybackSource(
            fileId = Video.fileId.value,
            kind = kind,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )

    private companion object {
        val Video = PlaybackTarget(FilesItemId(42L), "episode.mkv")
        val SamePosition = 42_000L..43_000L
        val DecoderFailure = PlaybackException("synthetic", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val UnsupportedVideo = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()),
                    false,
                    intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
                    booleanArrayOf(false),
                ),
            ),
        )
    }
}
