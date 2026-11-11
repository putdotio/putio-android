package io.putdotio.android.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.AudioSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvTrackRecoveryTest {
    @Test
    fun aSubtitleThatFailsToLoadIsDroppedSoPlaybackContinues() {
        val recovery = TvTrackRecovery(setOf(FOUND, FOLDER))

        assertFalse(recovery.loadFailed(C.TRACK_TYPE_UNKNOWN, VIDEO, gaveUp = true, atMillis = 0L))
        assertNull(recovery.recover(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, atMillis = 0L))

        assertTrue(recovery.loadFailed(C.TRACK_TYPE_UNKNOWN, FOUND, gaveUp = true, atMillis = 1_000L))
        assertEquals(
            TvTrackRecoveryStep.DropSubtitles(setOf(FOUND), notify = false),
            recovery.recover(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, atMillis = 2_000L),
        )
        assertNull(
            "A second failure is the video's own",
            recovery.recover(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, atMillis = 3_000L),
        )
    }

    @Test
    fun aSubtitleErrorThrownWhileTheLoaderStillRetriesIsDroppedWithANotice() {
        val recovery = TvTrackRecovery(setOf(FOUND))

        assertFalse(recovery.loadFailed(C.TRACK_TYPE_UNKNOWN, FOUND, gaveUp = false, atMillis = 0L))
        assertFalse(recovery.loadFailed(C.TRACK_TYPE_UNKNOWN, FOUND, gaveUp = false, atMillis = 1_000L))
        assertEquals(
            TvTrackRecoveryStep.DropSubtitles(setOf(FOUND), notify = true),
            recovery.recover(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, atMillis = 1_500L),
        )
    }

    @Test
    fun aSubtitleThatLoadsOnRetryIsNotBlamedForALaterVideoError() {
        val recovery = TvTrackRecovery(setOf(FOUND))

        recovery.loadFailed(C.TRACK_TYPE_UNKNOWN, FOUND, gaveUp = false, atMillis = 0L)
        recovery.loadCompleted(FOUND)
        assertNull(recovery.recover(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, atMillis = 1_000L))
    }

    @Test
    fun aManifestSubtitleThatFailsTurnsSubtitlesOffWithoutDroppingSidecars() {
        val recovery = TvTrackRecovery(setOf(FOUND))

        assertTrue(recovery.loadFailed(C.TRACK_TYPE_TEXT, MANIFEST_SEGMENT, gaveUp = true, atMillis = 0L))
        assertEquals(
            TvTrackRecoveryStep.DropSubtitles(emptySet(), notify = false),
            recovery.recover(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, atMillis = 0L),
        )
    }

    @Test
    fun aManifestSubtitlePlaylistThatFailsCountsAsASubtitleFailure() {
        val recovery = TvTrackRecovery(emptySet()) { setOf(SUBTITLE_PLAYLIST) }

        assertTrue(recovery.loadFailed(C.TRACK_TYPE_UNKNOWN, SUBTITLE_PLAYLIST, gaveUp = true, atMillis = 0L))
        assertEquals(
            TvTrackRecoveryStep.DropSubtitles(emptySet(), notify = false),
            recovery.recover(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, atMillis = 0L),
        )
    }

    @Test
    fun aNetworkDropLongAfterASubtitleFailedIsNotBlamedOnTheSubtitle() {
        val recovery = TvTrackRecovery(emptySet())

        recovery.loadFailed(C.TRACK_TYPE_TEXT, MANIFEST_SEGMENT, gaveUp = true, atMillis = 0L)
        assertNull(recovery.recover(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, atMillis = 60_000L))
        assertTrue(
            "A later failure gets its own notice",
            recovery.loadFailed(C.TRACK_TYPE_TEXT, MANIFEST_SEGMENT, gaveUp = true, atMillis = 61_000L),
        )
    }

    @Test
    fun aDecoderFailureAfterAnAudioSwitchRestoresTheTrackThatPlayed() {
        val recovery = TvTrackRecovery(emptySet())
        val before = TvAudioSwitch(TrackSelectionParameters.Builder().build(), AudioSelection.Automatic)

        recovery.audioSwitching(before, atMillis = 0L)
        val step = recovery.recover(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, atMillis = 0L)
        assertSame(before, (step as TvTrackRecoveryStep.RestoreAudio).switch)

        recovery.audioSwitching(before, atMillis = 0L)
        recovery.audioPlaying()
        assertNull(recovery.recover(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, atMillis = 0L))
    }

    @Test
    fun aDecoderFailureLongAfterAnAudioSwitchIsTheVideosOwn() {
        val recovery = TvTrackRecovery(emptySet())

        recovery.audioSwitching(
            TvAudioSwitch(TrackSelectionParameters.Builder().build(), AudioSelection.Automatic),
            atMillis = 0L,
        )
        assertNull(recovery.recover(PlaybackException.ERROR_CODE_DECODING_FAILED, atMillis = 60_000L))
    }

    @Test
    fun restoringAudioKeepsTheSubtitlePickMadeSinceTheSwitch() {
        val audio = TrackGroup(Format.Builder().setId("audio").setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        val text = TrackGroup(Format.Builder().setId("text").setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val before = TrackSelectionParameters.Builder()
            .setOverrideForType(TrackSelectionOverride(audio, 0))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val now = TrackSelectionParameters.Builder()
            .setOverrideForType(TrackSelectionOverride(text, 0))
            .build()

        val restored = now.withAudioOf(before)

        assertEquals(setOf(audio, text), restored.overrides.keys)
        assertFalse(C.TRACK_TYPE_TEXT in restored.disabledTrackTypes)
    }

    private companion object {
        const val VIDEO = "https://api.put.io/v2/files/42/hls/media.m3u8"
        const val FOUND = "https://api.put.io/v2/files/42/subtitles/os-en"
        const val FOLDER = "https://api.put.io/v2/files/42/subtitles/folder-en"
        const val SUBTITLE_PLAYLIST = "https://api.put.io/hls/playlist/subs/en.m3u8"
        const val MANIFEST_SEGMENT = "https://api.put.io/hls/playlist/subs/segment.vtt"
    }
}
