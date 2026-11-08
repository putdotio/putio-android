package io.putdotio.android

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAccount
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastDevice
import io.putdotio.android.playback.CastGateway
import io.putdotio.android.playback.CastPrepared
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastRemoteStatus
import io.putdotio.android.playback.MOBILE_CAST_RETRY_TAG
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackAnalyticsReporter
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackCastPlaylistSubtitle
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackStartup
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.castMediaFor
import io.putdotio.android.playback.toCastMedia
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun aScreenOnAVideoThatFinishesOnTheReceiverFollowsItToTheNextEpisode() {
        val account = EpisodesAccount()
        cast.autoplay.follow(SESSION, account)
        compose.setContent {
            val state by playback.state.collectAsState()
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    onPlaybackEnded = { playback.dispatch(PlaybackEvent.PlayerEnded) },
                    playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                    cast = cast,
                    castMedia = { source, _ ->
                        val file = requireNotNull(source)
                        PlaybackRepositoryResult.Success(requireNotNull(file.toCastMedia(episode(file.fileId), TOKEN)))
                    },
                    onPlay = { playback.dispatch(PlaybackEvent.NextChosen(it)) },
                )
            }
        }
        compose.runOnIdle { assertEquals(listOf(1L), gateway.loads) }

        gateway.report(1L, CastRemoteState.Playing)
        gateway.report(1L, CastRemoteState.Ended)

        compose.runOnIdle {
            assertEquals(Episode2, playback.state.value.target)
            assertEquals(listOf(1L, 2L), gateway.loads)
        }

        account.autoplay = false
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

    @Test
    fun anHlsVideoAsksTheReceiverForThePlaylistSubtitleTheAccountSelects() {
        val hls = PlaybackController(Episode1, HlsEpisode, scope)
        compose.setContent {
            val state by hls.state.collectAsState()
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
                    subtitleStartupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
                )
            }
        }

        try {
            compose.runOnIdle {
                assertEquals(listOf(PlaybackCastPlaylistSubtitle(null)), gateway.sent.map { it.playlistSubtitle })
            }
        } finally {
            hls.close()
        }
    }

    @Test
    fun audioReopenedWithoutASourceStartsOnTheReceiverOnceItsSourceIsKnown() {
        val song = PlaybackTarget(FilesItemId(3L), "song.mp3", PlaybackMediaType.AUDIO)
        val audio = PlaybackController(song, Episodes, scope, PlaybackStartup.AttachAudioSession)
        val starts = mutableListOf<PlaybackContent>()
        val analytics = object : PlaybackAnalyticsReporter {
            override fun castStarted() {
                starts += audio.state.value.content
            }
        }
        try {
            compose.setContent {
                val state by audio.state.collectAsState()
                PutioTheme {
                    MobilePlayerScreen(
                        state = state,
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        onBack = {},
                        analytics = analytics,
                        playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                        onSourceRequired = { audio.dispatch(PlaybackEvent.SourceRequired(it)) },
                        cast = cast,
                        castMedia = { source, _ ->
                            PlaybackRepositoryResult.Success(
                                requireNotNull(requireNotNull(source).toCastMedia(song, "download-token")),
                            )
                        },
                    )
                }
            }

            compose.runOnIdle {
                assertEquals(listOf(3L), gateway.loads)
                assertEquals(listOf(PlaybackContent.Ready::class), starts.map { it::class })
            }
        } finally {
            audio.close()
        }
    }

    @Test
    fun aStreamedOriginalVideoWaitsForItsConversionInsteadOfReachingTheReceiver() {
        val repository = UnconvertedMkv()
        val original = PlaybackController(Mkv, repository, scope)
        compose.setContent {
            val state by original.state.collectAsState()
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    onOriginalRejected = { reason, positionMillis ->
                        original.dispatch(PlaybackEvent.OriginalRejected(reason, positionMillis))
                    },
                    playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                    cast = cast,
                    castMedia = { source, key -> repository.castMediaFor(Mkv, source, key) },
                )
            }
        }

        compose.runOnIdle {
            assertTrue(original.state.value.content is PlaybackContent.Conversion)
            assertEquals(false, repository.resolved.first())
            assertTrue(repository.resolved.drop(1).isNotEmpty() && repository.resolved.drop(1).all { it })
            assertEquals(listOf(true), repository.conversions)
            assertTrue(gateway.loads.isEmpty())
        }
        original.close()
    }

    @Test
    fun aDirectPlayOriginalWithNoConvertedCopyCastsAsStandard() {
        val repository = OriginalWithoutConvertedCopy()
        val original = PlaybackController(Mp4, repository, scope)
        compose.setContent {
            val state by original.state.collectAsState()
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    onOriginalRejected = { reason, positionMillis ->
                        original.dispatch(PlaybackEvent.OriginalRejected(reason, positionMillis))
                    },
                    playerFactory = MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
                    cast = cast,
                    castMedia = { source, key -> repository.castMediaFor(Mp4, source, key) },
                )
            }
        }

        compose.waitUntil(5_000L) { gateway.loads.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(PlaybackSourceKind.HLS, (original.state.value.content as PlaybackContent.Ready).source.kind)
            assertEquals(listOf(Mp4.fileId.value), gateway.loads)
            assertEquals("application/x-mpegurl", gateway.sent.single().contentType.lowercase())
        }
        original.close()
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
        override val movesHere = MutableStateFlow(false)
        override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
        val loads = mutableListOf<Long>()
        val sent = mutableListOf<PlaybackCastMedia>()

        override fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float) {
            loads += media.fileId
            sent += media
        }

        override fun startDiscovery() = Unit

        override fun stopDiscovery() = Unit

        override fun select(deviceId: String) = Unit

        override fun stopCasting() = Unit

        override fun endForSignOut() = Unit

        override fun localNetworkAnswered() = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMillis: Long) = Unit

        override fun setSpeed(speed: Float) = Unit
    }

    /** Two episodes in one folder: the first one's next is the second, which is the last. */
    private object Episodes : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(target.fileId.value)))

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
            if (target == Episode1) PlaybackNextResult.Found(Episode2) else PlaybackNextResult.Ended
    }

    /** An episode put.io streams as HLS, whose playlist carries its subtitles. */
    private object HlsEpisode : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(
                    PlaybackSource(
                        fileId = target.fileId.value,
                        kind = PlaybackSourceKind.HLS,
                        url = PutioCredentialUrl.of(
                            "https://api.put.io/v2/files/${target.fileId.value}/hls/media.m3u8",
                        ),
                        startFromSeconds = 0.0,
                        subtitles = PlaybackSubtitles.Embedded,
                    ),
                ),
            )

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    private class UnconvertedMkv : PlaybackRepository {
        val resolved = mutableListOf<Boolean>()
        val conversions = mutableListOf<Boolean>()

        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            resolved += convertedOnly
            return PlaybackRepositoryResult.Success(
                if (convertedOnly) {
                    PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable)
                } else {
                    PlaybackResolution.Ready(source(target.fileId.value).copy(kind = PlaybackSourceKind.ORIGINAL))
                },
            )
        }

        override suspend fun startConversion(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            conversions += convertedOnly
            return PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))
        }

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    private class OriginalWithoutConvertedCopy : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                when {
                    !convertedOnly -> PlaybackResolution.Ready(sourced(target, PlaybackSourceKind.ORIGINAL))
                    target.method == StreamingMethod.Hls ->
                        PlaybackResolution.Ready(sourced(target, PlaybackSourceKind.HLS))
                    else -> PlaybackResolution.Unsupported(PutioFileType.VIDEO)
                },
            )

        override suspend fun castMedia(
            target: PlaybackTarget,
            source: PlaybackSource,
            activeSubtitleKey: String?,
        ): PlaybackRepositoryResult<PlaybackCastMedia> =
            PlaybackRepositoryResult.Success(requireNotNull(source.toCastMedia(target, TOKEN, activeSubtitleKey)))

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended

        private fun sourced(target: PlaybackTarget, kind: PlaybackSourceKind) =
            source(target.fileId.value).copy(kind = kind)
    }

    private class EpisodesAccount : CastAccount {
        override var autoplay = true

        override suspend fun next(target: PlaybackTarget): PlaybackTarget? =
            (Episodes.findNextVideo(target) as? PlaybackNextResult.Found)?.target

        override suspend fun prepare(target: PlaybackTarget): CastPrepared {
            val media = requireNotNull(source(target.fileId.value).toCastMedia(target, TOKEN))
            return CastPrepared(media, MediaItem.Builder().setMediaId(target.fileId.value.toString()).build(), 0L)
        }
    }

    private companion object {
        const val SESSION = "session"
        const val TOKEN = "download-token"
        val Episode1 = PlaybackTarget(FilesItemId(1L), "Show S01E01.mp4")
        val Episode2 = PlaybackTarget(FilesItemId(2L), "Show S01E02.mp4")
        val Mkv = PlaybackTarget(FilesItemId(3L), "Show S01E03.mkv")
        val Mp4 = PlaybackTarget(FilesItemId(4L), "Show S01E04.mp4")

        fun episode(fileId: Long) = listOf(Episode1, Episode2).single { it.fileId.value == fileId }

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
