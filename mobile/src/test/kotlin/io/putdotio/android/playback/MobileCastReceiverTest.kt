package io.putdotio.android.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.cast.CastMediaControlIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileCastReceiverTest {
    @Test
    fun aChoiceMadeMidSessionWaitsForTheSessionToEnd() {
        val selection = CastReceiverSelection(DEFAULT)

        assertNull(selection.want(PUTIO_RECEIVER_APPLICATION_ID, sessionLive = true))
        assertEquals(DEFAULT, selection.applied)
        assertEquals(PUTIO_RECEIVER_APPLICATION_ID, selection.sessionGone())
        assertNull(selection.sessionGone())
    }

    @Test
    fun aChoiceMadeBetweenSessionsAppliesAtOnceAndOnlyWhenItChanges() {
        val selection = CastReceiverSelection(DEFAULT)

        assertNull(selection.want(DEFAULT, sessionLive = false))
        val applied = selection.want(PUTIO_RECEIVER_APPLICATION_ID, sessionLive = false)
        assertEquals(PUTIO_RECEIVER_APPLICATION_ID, applied)
        assertNull(selection.want(DEFAULT, sessionLive = true))
        assertNull(selection.want(PUTIO_RECEIVER_APPLICATION_ID, sessionLive = true))
        assertNull(selection.sessionGone())
    }

    @Test
    fun withoutTheSwitchTheLoadIsTheDefaultReceiversPlainLoad() {
        val request = Media.loadRequest(12_000L, autoplay = true, speed = 1f, putioReceiver = false).toJson()

        assertEquals(PLAIN_LOAD, request.toString())
    }

    @Test
    fun thePutioReceiverGetsTheContractAndNoTokenBearingUrl() {
        val request = Media.loadRequest(12_000L, autoplay = true, speed = 1f, putioReceiver = true).toJson()
        val media = request.getJSONObject("media")
        val customData = request.getJSONObject("customData")

        assertFalse(media.has("contentUrl"))
        assertEquals("42", media.getString("contentId"))
        assertEquals(42L, media.getJSONObject("customData").getLong("putioFileId"))
        assertEquals(42L, customData.getLong("fileId"))
        assertEquals("hls", customData.getString("method"))
        assertEquals(12.0, customData.getDouble("position"), 0.0)
        assertEquals(TOKEN, customData.getString("token"))
        assertFalse(TOKEN in media.toString())
    }

    @Test
    fun mediaThePutioReceiverCannotPickGoesAsAPlainLoad() {
        val request = Media.copy(receiverLoad = null)
            .loadRequest(startMillis = 12_000L, autoplay = true, speed = 1f, putioReceiver = true).toJson()

        assertFalse(request.has("customData"))
        assertTrue(request.getJSONObject("media").has("contentUrl"))
    }

    @Test
    fun receiverStatusMessagesParse() {
        assertEquals(
            PutioReceiverState.Idle,
            parsePutioReceiverStatus("""{"type":"STATUS","state":{"status":"idle"}}"""),
        )
        assertEquals(
            PutioReceiverState.Loaded(42L),
            parsePutioReceiverStatus("""{"type":"STATUS","state":{"status":"loaded","fileId":42}}"""),
        )
        assertEquals(
            PutioReceiverState.Failed(null, "invalid_custom_data"),
            parsePutioReceiverStatus(
                """{"type":"STATUS","state":{"status":"failed","fileId":null,""" +
                    """"reason":"invalid_custom_data","detailedErrorCode":null}}""",
            ),
        )
        assertNull(parsePutioReceiverStatus("""{"type":"GET_STATUS"}"""))
        assertNull(parsePutioReceiverStatus("""{"type":"STATUS","state":{"status":"failed","fileId":42}}"""))
        assertNull(parsePutioReceiverStatus("not json"))
    }

    private companion object {
        const val DEFAULT = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        const val TOKEN = "download-token"
        const val URL = "https://api.put.io/v2/files/42/hls/media.m3u8?subtitle_key=all&oauth_token=$TOKEN"

        val Media = PlaybackCastMedia(
            fileId = 42L,
            title = "episode.mkv",
            mediaType = PlaybackMediaType.VIDEO,
            url = URL,
            contentType = "application/x-mpegURL",
            buffered = false,
            durationMillis = 60_000L,
            posterUrl = null,
            subtitles = emptyList(),
            activeSubtitleId = null,
            receiverLoad = PlaybackCastReceiverLoad(fileId = 42L, token = TOKEN),
        )

        const val PLAIN_LOAD =
            """{"media":{"contentId":"42","contentUrl":"https:\/\/api.put.io\/v2\/files\/42\/hls\/media.m3u8?""" +
                """subtitle_key=all&oauth_token=download-token","streamType":"NONE",""" +
                """"contentType":"application\/x-mpegURL",""" +
                """"metadata":{"metadataType":1,"title":"episode.mkv"},"duration":60,"tracks":[],""" +
                """"customData":{"putioFileId":42}},"autoplay":true,"currentTime":12,"playbackRate":1,""" +
                """"activeTrackIds":[],"requestId":0}"""
    }
}
