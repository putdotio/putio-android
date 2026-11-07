package io.putdotio.android.playback

import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SdkPlaybackOriginalResolutionTest {
    @Test
    fun standardPlaysConvertedEvenWhenTheDeviceAcceptsTheOriginal() =
        runBlocking {
            val mkv = resolve(PlaybackPreference.HLS, "h264-high-aac.mkv")
            val asf = resolve(PlaybackPreference.HLS, "wmv3-wmav2.asf")

            assertEquals(PlaybackSourceKind.HLS to HLS_CONVERTED, mkv.kind to mkv.request())
            assertEquals(PlaybackSourceKind.HLS to HLS_CONVERTED, asf.kind to asf.request())
        }

    @Test
    fun directPlayStreamsTheOriginalTheDeviceAccepts() =
        runBlocking {
            val mkv = resolve(PlaybackPreference.MP4, "h264-high-aac.mkv")

            assertEquals(PlaybackSourceKind.ORIGINAL to ORIGINAL_STREAM, mkv.kind to mkv.request())
        }

    @Test
    fun aTargetMethodPicksTheOriginalCheckForThatPlay() =
        runBlocking {
            val directPlay = resolve(PlaybackPreference.HLS, "h264-high-aac.mkv", StreamingMethod.File)
            val standard = resolve(PlaybackPreference.MP4, "h264-high-aac.mkv", StreamingMethod.Hls)

            assertEquals(PlaybackSourceKind.ORIGINAL to ORIGINAL_STREAM, directPlay.kind to directPlay.request())
            assertEquals(PlaybackSourceKind.HLS to HLS_CONVERTED, standard.kind to standard.request())
        }

    private suspend fun resolve(
        preference: PlaybackPreference,
        mediaInfo: String,
        method: StreamingMethod? = null,
    ): PlaybackSource {
        val repository = SdkPlaybackRepository(
            client(mediaInfo),
            playbackPreference = { preference },
            originalVideoPolicy = { OriginalVideoPolicy.Check },
            decoders = FakeDecoderInventory(PHONE),
        )
        val result = repository.resolve(PlaybackTarget(FilesItemId(42L), mediaInfo, method = method))
        val ready = (result as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready
        return ready.source
    }

    private fun PlaybackSource.request(): String =
        url.encodedPath + url.queryParameterNames.filter { it == "original" }.joinToString("") { "?$it=1" }

    private fun client(mediaInfo: String): PutioClient {
        val info = requireNotNull(javaClass.getResource("/media-info/$mediaInfo.json")).readText()
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val body = when (chain.request().url.encodedPath) {
                        "/v2/account/info" -> ACCOUNT_INFO
                        "/v2/files/42" -> file(mediaInfo, info)
                        "/v2/files/42/subtitles" -> """{"status":"OK","subtitles":[]}"""
                        else -> error("Unexpected ${chain.request().url.encodedPath}")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return PutioClient(PutioConfig(accessToken = "synthetic-access-token"), http)
    }

    private fun file(name: String, mediaInfo: String): String =
        """
        {"status":"OK","file":{"id":42,"name":"$name","parent_id":0,"size":42,
        "created_at":"2026-09-01T00:00:00Z","file_type":"VIDEO","is_mp4_available":false,
        "need_convert":false,"start_from":0,"media_info":$mediaInfo}}
        """.trimIndent()

    private companion object {
        const val ORIGINAL_STREAM = "/v2/files/42/stream"
        const val HLS_CONVERTED = "/v2/files/42/hls/media.m3u8"
        const val ACCOUNT_INFO =
            """
            {"status":"OK","info":{"user_id":1,"username":"u","mail":"u@example.com","avatar_url":"",
            "disk":{"avail":1,"size":1,"used":0},"settings":{"sort_by":"NAME_ASC"},
            "account_status":"active","download_token":"synthetic-download-token"}}
            """
        val PHONE = setOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_MP4V,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_MPEG,
        )
    }
}
