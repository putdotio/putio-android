package io.putdotio.android

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.AudioSelection
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackAudioSwitch
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFallbackReason
import io.putdotio.android.playback.PlaybackRequestId
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.PlaybackTrackFailure
import io.putdotio.android.playback.PlaybackTrackRecovery
import io.putdotio.android.playback.PlaybackTrackRecoveryStep
import io.putdotio.android.playback.playbackState
import io.putdotio.android.playback.playbackTrackLoadListener
import io.putdotio.android.playback.sidecarSubtitleUris
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
@UnstableApi
class MobilePlayerReuseTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun directPlayFallbackStillRunsOnSecondVideoAndOriginalSwitchAfterFirstFrame() {
        val player = RecordingPlayer()
        val video1 = PlaybackTarget(FilesItemId(10L), "one.mkv")
        val video2 = PlaybackTarget(FilesItemId(20L), "two.mkv")
        var state by mutableStateOf(readyState(video1, PlaybackSourceKind.MP4, "https://example.com/1.mp4"))
        val rejections = mutableListOf<PlaybackFallbackReason>()
        var failures = 0
        compose.setBuiltContent {
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> failures += 1 },
                    onOriginalRejected = { reason, _ -> rejections += reason; true },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { _, _ -> player },
                )
            }
        }
        compose.runOnIdle { player.renderFirstFrame() }
        compose.runOnIdle { state = readyState(video2, PlaybackSourceKind.ORIGINAL, "https://example.com/2.mkv") }
        compose.runOnIdle {
            assertEquals(2, player.mediaItemUpdates)
            player.fail(PlaybackException("codec", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
        compose.runOnIdle {
            assertEquals(1, rejections.size)
            assertEquals(0, failures)
            state = readyState(video2, PlaybackSourceKind.MP4, "https://example.com/2-converted.mp4")
        }
        compose.runOnIdle {
            assertEquals(3, player.mediaItemUpdates)
            player.renderFirstFrame()
            state = readyState(video2, PlaybackSourceKind.ORIGINAL, "https://example.com/2-orig.mkv")
        }
        compose.runOnIdle {
            assertEquals(4, player.mediaItemUpdates)
            player.fail(PlaybackException("codec2", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
        compose.runOnIdle {
            assertEquals(2, rejections.size)
            assertEquals(0, failures)
        }
    }

    @Test
    fun itemSwitchClearsFailurePositionAndEndedFlagBeforeDispose() {
        val players = mutableListOf<RecordingPlayer>()
        val video1 = PlaybackTarget(FilesItemId(10L), "one.mkv")
        val video2 = PlaybackTarget(FilesItemId(20L), "two.mkv")
        var state by mutableStateOf(readyState(video1, PlaybackSourceKind.ORIGINAL, "https://example.com/1.mkv"))
        var endedCalls = 0
        compose.setBuiltContent {
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onOriginalRejected = { _, _ -> true },
                    autoplayNextVideo = true,
                    onPlaybackEnded = { endedCalls += 1 },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { _, _ -> RecordingPlayer().also(players::add) },
                )
            }
        }
        compose.runOnIdle {
            players.single().movePositionTo(45_000L)
            players.single().fail(PlaybackException("codec", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            players.single().prepare()
            players.single().updatePlaybackState(Media3Player.STATE_ENDED)
        }
        compose.runOnIdle {
            assertEquals(1, endedCalls)
            state = readyState(video2, PlaybackSourceKind.MP4, "https://example.com/2.mp4")
        }
        compose.runOnIdle {
            assertEquals(2, players.single().mediaItemUpdates)
            players.single().updatePlaybackState(Media3Player.STATE_ENDED)
        }
        compose.runOnIdle {
            assertEquals(2, endedCalls)
            players.single().movePositionTo(12_000L)
            state = playbackState(video2, PlaybackContent.Loading(PlaybackRequestId(3L)), nextRequestValue = 4L)
        }
        compose.runOnIdle {
            assertTrue(players.single().released)
            state = readyState(video2, PlaybackSourceKind.MP4, "https://example.com/2.mp4")
        }
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals(12_000L, players.last().currentPosition)
        }
    }

    @Test
    fun itemSwitchClearsPendingAudioRecovery() {
        val player = RecordingPlayer()
        val video1 = PlaybackTarget(FilesItemId(10L), "one.mkv")
        val video2 = PlaybackTarget(FilesItemId(20L), "two.mkv")
        var state by mutableStateOf(readyState(video1, PlaybackSourceKind.MP4, "https://example.com/1.mp4"))
        var failures = 0
        compose.setBuiltContent {
            PutioTheme {
                MobilePlayerScreen(
                    state = state,
                    onRetry = {},
                    onPlayerFailure = { _, _ -> failures += 1 },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { _, _ -> player },
                )
            }
        }
        compose.runOnIdle { player.updateTracks(twoAudioTracks(twoAudioTrackGroup())) }
        compose.onNodeWithContentDescription("Audio: English").performClick()
        compose.onNode(hasText("German") and isSelectable()).performClick()
        compose.runOnIdle { state = readyState(video2, PlaybackSourceKind.MP4, "https://example.com/2.mp4") }
        compose.runOnIdle {
            assertEquals(2, player.mediaItemUpdates)
            player.fail(PlaybackException("audio", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
            assertEquals(1, failures)
        }
    }

    @Test
    fun delayedAnalyticsCallbacksFromReplacedItemAreIgnored() {
        val player = RecordingPlayer()
        val dropped = mutableListOf<PlaybackTrackRecoveryStep.DropSubtitles>()
        val recovery = PlaybackTrackRecovery(
            sidecarUris = { player.currentMediaItem?.sidecarSubtitleUris().orEmpty() },
        )
        val listener = playbackTrackLoadListener(player, { false }, recovery, dropped::add)
        val audioSwitch = PlaybackAudioSwitch(TrackSelectionParameters.Builder().build(), AudioSelection.Automatic)
        try {
            player.setMediaItem(item("10", "https://example.com/1.mp4", "https://example.com/a.vtt"))
            val timelineA = player.currentTimeline
            player.setMediaItem(item("20", "https://example.com/2.mp4", "https://example.com/b.vtt"))
            recovery.reset()
            recovery.audioSwitching(audioSwitch, atMillis = 0L)
            val subUri = "https://example.com/b.vtt"
            val subLoad = LoadEventInfo.Builder(1L, DataSpec(Uri.parse(subUri)), 0L).build()
            val data = MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_TEXT, null, 0, null, 0L, 0L)
            val now = player.currentTimeline
            listener.onLoadError(eventTime(timelineA, now), subLoad, data, IOException(), true)
            listener.onLoadError(eventTime(Timeline.EMPTY, now), subLoad, data, IOException(), true)
            listener.onAudioPositionAdvancing(eventTime(timelineA, now), 0L)
            assertTrue(dropped.isEmpty())
            val audioFail = PlaybackTrackFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            assertTrue(recovery.recover(audioFail, 100L) is PlaybackTrackRecoveryStep.RestoreAudio)
            val timelineBStream = player.currentTimeline
            player.setMediaItem(item("20", "https://example.com/2-orig.mkv", subUri))
            listener.onLoadError(eventTime(timelineBStream, player.currentTimeline), subLoad, data, IOException(), true)
            assertTrue(dropped.isEmpty())

            player.setMediaItem(item("20", "https://example.com/2.mp4", subUri))
            listener.onLoadError(eventTime(timelineBStream, player.currentTimeline), subLoad, data, IOException(), true)
            assertTrue(dropped.isEmpty())

            val current = player.currentTimeline
            listener.onLoadError(eventTime(current, current), subLoad, data, IOException(), true)
            assertEquals(listOf(PlaybackTrackRecoveryStep.DropSubtitles(setOf(subUri), true)), dropped)
        } finally {
            player.release()
        }
    }

    private fun eventTime(eventTimeline: Timeline, currentTimeline: Timeline) =
        AnalyticsListener.EventTime(0L, eventTimeline, 0, null, 0L, currentTimeline, 0, null, 0L, 0L)

    private fun item(id: String, uri: String, subtitleUri: String): MediaItem {
        val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUri))
            .setMimeType(MimeTypes.TEXT_VTT)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setSubtitleConfigurations(listOf(subtitle))
            .build()
    }

    private fun twoAudioTrackGroup() =
        TrackGroup(
            Format.Builder().setId("en").setLanguage("en").setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
            Format.Builder().setId("de").setLanguage("de").setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
        )

    private fun twoAudioTracks(group: TrackGroup): Tracks {
        val statuses = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED)
        return Tracks(listOf(Tracks.Group(group, false, statuses, booleanArrayOf(true, false))))
    }

    private fun readyState(target: PlaybackTarget, kind: PlaybackSourceKind, url: String) =
        playbackState(
            target = target,
            content = PlaybackContent.Ready(
                PlaybackSource(
                    fileId = target.fileId.value,
                    kind = kind,
                    url = PutioCredentialUrl.of(url),
                    startFromSeconds = 0.0,
                    subtitles = PlaybackSubtitles.None,
                ),
            ),
            nextRequestValue = 2L,
        )
}
