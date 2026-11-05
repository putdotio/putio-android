package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PlaybackSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvOriginalFallbackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val players = mutableListOf<FakePlayer>()
    private val asked = mutableListOf<Boolean>()
    private val controller = PlaybackController(
        PlaybackTarget(FilesItemId(9), "Sintel.mkv", PlaybackMediaType.VIDEO),
        object : PlaybackRepository {

            override suspend fun resolve(
                target: PlaybackTarget,
                convertedOnly: Boolean,
            ): PlaybackRepositoryResult<PlaybackResolution> {
                asked += convertedOnly
                val kind = if (convertedOnly) PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL
                return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source().copy(kind = kind)))
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
        },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    @After
    fun close() = controller.close()

    @Test
    fun tracksTheTvCannotDecodePlayConvertedFromTheSamePosition() {
        showRoute()
        compose.runOnIdle {
            players.single().advanceTo(42_000L)
            players.single().showTracks(UnsupportedVideo)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(false, true), asked)
            assertEquals(PlaybackSourceKind.HLS, readyKind())
            assertEquals(2, players.size)
            assertEquals(42_000L, players.last().startPositionMillis)
        }
    }

    @Test
    fun anEarlyDecoderFailureFallsBackButALateOneFails() {
        showRoute()
        compose.runOnIdle {
            players.single().fail(61_000L, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(PlaybackSourceKind.HLS, readyKind())
            assertEquals(61_000L, players.last().startPositionMillis)
            players.last().renderFirstFrame()
        }
        compose.runOnIdle { players.last().fail(62_000L, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(controller.state.value.content is PlaybackContent.Failed)
            assertEquals(listOf(false, true), asked)
        }
    }

    private fun showRoute() {
        compose.setTvContent {
            TvPlaybackRoute(
                controller = controller,
                onExit = {},
                onSessionRejected = {},
                playerFactory = { _, _ -> FakePlayer().also(players::add) },
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(PlaybackSourceKind.ORIGINAL, readyKind()) }
    }

    private fun readyKind(): PlaybackSourceKind? =
        (controller.state.value.content as? PlaybackContent.Ready)?.source?.kind

    private companion object {
        val UnsupportedVideo = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build()),
                    false,
                    intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
                    booleanArrayOf(false),
                ),
            ),
        )
    }
}
