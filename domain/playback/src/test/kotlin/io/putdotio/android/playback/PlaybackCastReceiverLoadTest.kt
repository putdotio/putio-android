package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCastReceiverLoadTest {
    @Test
    fun standardHlsCarriesTheFileTokenAndPlaylistFilter() {
        val load = hls("max_subtitle_count=0&subtitle_languages=eng,tur&subtitle_key=all")

        assertEquals(
            PlaybackCastReceiverLoad(
                fileId = 42L,
                token = DOWNLOAD,
                maxSubtitleCount = 0,
                subtitleLanguages = listOf("eng", "tur"),
            ),
            load,
        )
        val data = load!!.json(positionMillis = 90_500L)
        assertEquals(
            Json.parseToJsonElement(
                """{"fileId":42,"method":"hls","position":90.5,"token":"$DOWNLOAD",""" +
                    """"maxSubtitleCount":0,"subtitleLanguages":["eng","tur"]}""",
            ),
            data,
        )
        assertFalse(load.toString().contains(DOWNLOAD))
    }

    @Test
    fun aFilterTheContractCannotCarryIsLeftToTheReceiver() {
        val load = hls("max_subtitle_count=-1&subtitle_languages=en")!!

        assertNull(load.maxSubtitleCount)
        assertNull(load.subtitleLanguages)
        assertFalse("maxSubtitleCount" in load.json(0L))
        assertFalse("subtitleLanguages" in load.json(0L))
    }

    @Test
    fun onlyStandardHlsVideoGoesToThePutioReceiver() {
        assertNull(hls("original=1"))
        assertNull(hls("", target = Video.copy(mediaType = PlaybackMediaType.AUDIO)))
        val mp4 = source(PlaybackSourceKind.MP4, "https://api.put.io/v2/files/42/mp4/download")
        assertNull(mp4.toReceiverLoad(Video, DOWNLOAD))
    }

    @Test
    fun castMediaCarriesItsReceiverLoad() {
        val media = source(PlaybackSourceKind.HLS, "https://api.put.io/v2/files/42/hls/media.m3u8?oauth_token=session")
            .toCastMedia(Video, DOWNLOAD)

        assertEquals(DOWNLOAD, media?.receiverLoad?.token)
        assertFalse(media.toString().contains(DOWNLOAD))
    }

    @Test
    fun mediaInfoKeepsVideoAndAudioFirstAndAsManyStreamsAsFit() {
        val subtitles = List(400) { PutioMediaStream(codecType = "subtitle", codecName = "subrip-$it") }
        val info = PutioMediaInfo(
            mimeType = "video/x-matroska",
            format = PutioMediaFormat(name = "matroska,webm", duration = 60.0, bitRate = 1_000L),
            streams = subtitles + PutioMediaStream(codecType = "audio", codecName = "aac") +
                PutioMediaStream(codecType = "video", codecName = "h264", width = 1920),
        )
        val load = PlaybackCastReceiverLoad(fileId = 42L, token = DOWNLOAD, mediaInfo = info)

        val small = load.customData(0L, maxBytes = 4_096)
        assertTrue(small.toByteArray().size <= 4_096)
        val streams = Json.parseToJsonElement(small).jsonObject["media_info"]!!.jsonObject["streams"]!!.jsonArray
        assertEquals("video", streams[0].jsonObject["codec_type"]!!.jsonPrimitive.content)
        assertEquals("audio", streams[1].jsonObject["codec_type"]!!.jsonPrimitive.content)
        assertTrue(streams.size in 3 until 402)
        assertFalse("null" in small)

        val none = load.customData(0L, maxBytes = 200)
        assertFalse("media_info" in Json.parseToJsonElement(none).jsonObject)
    }

    @Test
    fun incompleteMediaInfoStaysOut() {
        val emptyStreams = PlaybackCastReceiverLoad(42L, DOWNLOAD, mediaInfo = PutioMediaInfo(streams = emptyList()))
        assertFalse("media_info" in emptyStreams.json(0L))

        val blankFormatName = PlaybackCastReceiverLoad(
            42L,
            DOWNLOAD,
            mediaInfo = PutioMediaInfo(
                format = PutioMediaFormat(name = ""),
                streams = listOf(PutioMediaStream(codecType = "video", codecName = "h264")),
            ),
        )
        assertFalse("media_info" in blankFormatName.json(0L))
    }

    @Test
    fun customDataMatchesSharedGoldenFixtures() {
        val cases = listOf(
            "minimal.json" to
                PlaybackCastReceiverLoad(fileId = 42L, token = DOWNLOAD).customData(0L, MAX_BYTES),
            "hls-with-position.json" to
                PlaybackCastReceiverLoad(fileId = 42L, token = DOWNLOAD).customData(90_000L, MAX_BYTES),
            "file-method.json" to PlaybackCastReceiverLoad(
                fileId = 42L,
                token = DOWNLOAD,
                method = PlaybackCastMethod.FILE,
            ).customData(0L, MAX_BYTES),
            "subtitle-languages.json" to PlaybackCastReceiverLoad(
                fileId = 42L,
                token = DOWNLOAD,
                subtitleLanguages = listOf("eng", "tur"),
            ).customData(0L, MAX_BYTES),
            "subtitle-max-count-zero.json" to PlaybackCastReceiverLoad(
                fileId = 42L,
                token = DOWNLOAD,
                maxSubtitleCount = 0,
            ).customData(0L, MAX_BYTES),
            "public-share-token.json" to
                PlaybackCastReceiverLoad(fileId = 42L, token = "public-share-token")
                    .customData(0L, MAX_BYTES),
        )
        for ((name, actual) in cases) {
            assertEquals(name, Json.parseToJsonElement(fixture(name)), Json.parseToJsonElement(actual))
        }
    }

    @Test
    fun mediaInfoTrimLimitMatchesSharedGoldenFixture() {
        val trimFixtureText = fixture("media-info-trim-limit.json")
        val trimFixture = Json.parseToJsonElement(trimFixtureText).jsonObject
        val trimBudget = trimFixture.toString().toByteArray(Charsets.UTF_8).size
        val extraSubtitles = List(50) {
            PutioMediaStream(codecType = "subtitle", codecName = "hdmv_pgs_subtitle")
        }
        val trimFixtureInfo = trimFixture["media_info"]!!.jsonObject
        val fixtureStreams = trimFixtureInfo["streams"]!!.jsonArray
        val videoStream = PutioMediaStream(
            codecType = "video",
            codecName = "h264",
            rfc6381Codec = "avc1.640029",
            width = 1920,
            height = 1080,
            profile = "High",
            level = 41,
            bitDepth = 8,
            isHdr = false,
            isDefault = true,
        )
        val audioStream = PutioMediaStream(
            codecType = "audio",
            codecName = "aac",
            rfc6381Codec = "mp4a.40.2",
            channels = 2,
            isDefault = true,
        )
        val subtitleStreams = List(fixtureStreams.size - 2) {
            PutioMediaStream(codecType = "subtitle", codecName = "hdmv_pgs_subtitle")
        }
        val trimLoad = PlaybackCastReceiverLoad(
            fileId = 42L,
            token = DOWNLOAD,
            mediaInfo = PutioMediaInfo(
                mimeType = "video/x-matroska",
                format = PutioMediaFormat(name = "matroska,webm", duration = 100.5, bitRate = 4_000_000L),
                streams = extraSubtitles + subtitleStreams + audioStream + videoStream,
                playbackIssues = emptyList(),
            ),
        )
        assertEquals(trimFixture, Json.parseToJsonElement(trimLoad.customData(0L, maxBytes = trimBudget)))
        val trimmedSmaller =
            Json.parseToJsonElement(trimLoad.customData(0L, maxBytes = trimBudget - 1)).jsonObject
        assertEquals(
            fixtureStreams.size - 1,
            trimmedSmaller["media_info"]!!.jsonObject["streams"]!!.jsonArray.size,
        )
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/cast-load/$name")) { "Missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    private fun PlaybackCastReceiverLoad.json(positionMillis: Long): JsonObject =
        Json.parseToJsonElement(customData(positionMillis, maxBytes = MAX_BYTES)).jsonObject

    private fun hls(query: String, target: PlaybackTarget = Video) =
        source(PlaybackSourceKind.HLS, "https://api.put.io/v2/files/42/hls/media.m3u8?$query")
            .toReceiverLoad(target, DOWNLOAD)

    private fun source(kind: PlaybackSourceKind, url: String) =
        PlaybackSource(
            fileId = 42L,
            kind = kind,
            url = PutioCredentialUrl.of(url),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Embedded,
        )

    private companion object {
        const val MAX_BYTES = 64 * 1024
        const val DOWNLOAD = "download-token"
        val Video = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
