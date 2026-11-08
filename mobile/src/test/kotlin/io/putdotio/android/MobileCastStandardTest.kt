package io.putdotio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.castMediaFor
import io.putdotio.android.playback.toCastMedia
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileCastStandardTest {
    private val video = PlaybackTarget(FilesItemId(3L), "Show.mkv")
    private val audio = PlaybackTarget(FilesItemId(4L), "Song.flac", PlaybackMediaType.AUDIO)
    private val resolved = mutableListOf<Pair<PlaybackTarget, Boolean>>()
    private val cast = mutableListOf<PlaybackSource>()
    private val repository = object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            resolved += target to convertedOnly
            return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(PlaybackSourceKind.HLS)))
        }

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended

        override suspend fun castMedia(
            target: PlaybackTarget,
            source: PlaybackSource,
            activeSubtitleKey: String?,
        ): PlaybackRepositoryResult<PlaybackCastMedia> {
            cast += source
            return PlaybackRepositoryResult.Success(requireNotNull(source.toCastMedia(target, "download-token")))
        }
    }

    @Test
    fun aVideoPlayingFromAFileIsCastAsStandard() = runBlocking {
        listOf(PlaybackSourceKind.MP4, PlaybackSourceKind.ORIGINAL).forEach { kind ->
            repository.castMediaFor(video, source(kind), null)
        }
        repository.castMediaFor(video, null, null)

        val standard = video.copy(method = StreamingMethod.Hls) to true
        assertEquals(listOf(standard, standard, standard), resolved)
        assertEquals(listOf(PlaybackSourceKind.HLS), cast.map { it.kind }.distinct())
    }

    @Test
    fun aVideoAlreadyOnHlsIsCastAsItIs() = runBlocking {
        val hls = source(PlaybackSourceKind.HLS)

        repository.castMediaFor(video, hls, null)

        assertTrue(resolved.isEmpty())
        assertEquals(listOf(hls), cast)
    }

    @Test
    fun audioKeepsItsOriginalSource() = runBlocking {
        val original = source(PlaybackSourceKind.ORIGINAL)

        repository.castMediaFor(audio, original, null)
        repository.castMediaFor(audio, null, null)

        assertEquals(listOf(audio to false), resolved)
        assertEquals(original, cast.first())
    }

    private fun source(kind: PlaybackSourceKind) =
        PlaybackSource(
            fileId = 3L,
            kind = kind,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/3/source"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
}
