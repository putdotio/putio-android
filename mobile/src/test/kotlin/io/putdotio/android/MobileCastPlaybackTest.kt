package io.putdotio.android

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastDevice
import io.putdotio.android.playback.CastGateway
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastRemoteStatus
import io.putdotio.android.playback.MOBILE_CAST_RETRY_TAG
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.toCastMedia
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
@UnstableApi
class MobileCastPlaybackTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gateway = ReceiverGateway()
    private val cast = MobileCastController(gateway, scope) { 0L }
    private val playback = PlaybackController(Episode1, Episodes, scope)

    @After
    fun tearDown() {
        playback.close()
        scope.cancel()
    }

    @Test
    fun aVideoThatFinishesOnTheReceiverCastsTheNextEpisodeOnlyWithAutoplayOn() {
        var autoplay by mutableStateOf(true)
        compose.setContent {
            val state by playback.state.collectAsState()
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    autoplayNextVideo = autoplay,
                    onPlaybackEnded = { playback.dispatch(PlaybackEvent.PlayerEnded) },
                    playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                    cast = cast,
                    castMedia = { source, _ ->
                        val file = requireNotNull(source)
                        val target = listOf(Episode1, Episode2).single { it.fileId.value == file.fileId }
                        PlaybackRepositoryResult.Success(requireNotNull(file.toCastMedia(target, "download-token")))
                    },
                )
            }
        }
        compose.runOnIdle { assertEquals(listOf(1L), gateway.loads) }

        gateway.report(1L, CastRemoteState.Playing)
        gateway.report(1L, CastRemoteState.Ended)

        // The same session takes the next episode; nothing asks for the device again.
        compose.runOnIdle {
            assertEquals(Episode2, playback.state.value.target)
            assertEquals(listOf(1L, 2L), gateway.loads)
        }

        compose.runOnIdle { autoplay = false }
        gateway.report(2L, CastRemoteState.Playing)
        gateway.report(2L, CastRemoteState.Ended)

        compose.runOnIdle {
            assertEquals(Episode2, playback.state.value.target)
            assertEquals(PlaybackContent.Ready::class, playback.state.value.content::class)
            assertEquals(listOf(1L, 2L), gateway.loads)
        }
    }

    @Test
    fun aLookupThatFailsForAMomentCastsOnTryAgain() {
        var lookups = 0
        compose.setContent {
            val state by playback.state.collectAsState()
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                    cast = cast,
                    castMedia = { source, _ ->
                        lookups++
                        if (lookups == 1) {
                            val offline = PutioFailure.NetworkUnavailable(IllegalStateException("offline"))
                            PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(offline))
                        } else {
                            PlaybackRepositoryResult.Success(
                                requireNotNull(requireNotNull(source).toCastMedia(Episode1, "download-token")),
                            )
                        }
                    },
                )
            }
        }
        compose.runOnIdle { assertEquals(emptyList<Long>(), gateway.loads) }

        compose.onNodeWithTag(MOBILE_CAST_RETRY_TAG).performClick()

        compose.runOnIdle { assertEquals(listOf(1L), gateway.loads) }
        compose.onAllNodesWithTag(MOBILE_CAST_RETRY_TAG).assertCountEquals(0)
    }

    @Test
    fun aReceiverThatFailsToLoadTheFileLoadsItAgainOnTryAgain() {
        compose.setContent {
            val state by playback.state.collectAsState()
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                    cast = cast,
                    castMedia = { source, _ ->
                        PlaybackRepositoryResult.Success(
                            requireNotNull(requireNotNull(source).toCastMedia(Episode1, "download-token")),
                        )
                    },
                )
            }
        }
        compose.runOnIdle { assertEquals(listOf(1L), gateway.loads) }

        gateway.report(1L, CastRemoteState.Failed)
        compose.onNodeWithTag(MOBILE_CAST_RETRY_TAG).performClick()

        compose.runOnIdle { assertEquals(listOf(1L, 1L), gateway.loads) }
    }

    private fun ReceiverGateway.report(fileId: Long, state: CastRemoteState) {
        compose.runOnIdle {
            status.value = CastRemoteStatus(
                fileId = fileId,
                state = state,
                positionMillis = 0L,
                durationMillis = 10_000L,
                sampledAtMillis = 0L,
            )
        }
        compose.waitForIdle()
    }

    /** A connected receiver that records what it is sent; the test speaks for it. */
    private class ReceiverGateway : CastGateway {
        override val availability = MutableStateFlow<CastAvailability>(CastAvailability.Connected("Living room TV"))
        override val status = MutableStateFlow<CastRemoteStatus?>(null)
        override val endedStatus: CastRemoteStatus? = null
        override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
        val loads = mutableListOf<Long>()

        override fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float) {
            loads += media.fileId
        }

        override fun startDiscovery() = Unit

        override fun stopDiscovery() = Unit

        override fun select(deviceId: String) = Unit

        override fun stopCasting() = Unit

        override fun endForSignOut() = Unit

        override fun localNetworkAnswered(granted: Boolean) = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMillis: Long) = Unit

        override fun setSpeed(speed: Float) = Unit
    }

    /** Two episodes in one folder: the first one's next is the second, which is the last. */
    private object Episodes : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(target.fileId.value)))

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
            if (target == Episode1) PlaybackNextResult.Found(Episode2) else PlaybackNextResult.Ended
    }

    private companion object {
        val Episode1 = PlaybackTarget(FilesItemId(1L), "Show S01E01.mp4")
        val Episode2 = PlaybackTarget(FilesItemId(2L), "Show S01E02.mp4")

        fun source(fileId: Long) =
            PlaybackSource(
                fileId = fileId,
                kind = PlaybackSourceKind.MP4,
                url = PutioCredentialUrl.of("https://api.put.io/v2/files/$fileId/mp4/download"),
                startFromSeconds = 0.0,
                subtitles = PlaybackSubtitles.None,
            )
    }
}
