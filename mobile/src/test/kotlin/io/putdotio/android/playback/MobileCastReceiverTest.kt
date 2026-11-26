package io.putdotio.android.playback

import android.app.Application
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionTransferCallback
import com.google.android.gms.tasks.TaskCompletionSource
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream
import java.io.Closeable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileCastReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        CastReceiverStore.save(app, false)
        MobileCast.resetForTests()
    }
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
    fun aRefusedSwitchIsAskedForAgain() {
        val selection = CastReceiverSelection(DEFAULT)

        assertEquals(PUTIO_RECEIVER_APPLICATION_ID, selection.want(PUTIO_RECEIVER_APPLICATION_ID, sessionLive = false))
        selection.refused(PUTIO_RECEIVER_APPLICATION_ID, DEFAULT)

        assertEquals(DEFAULT, selection.applied)
        assertEquals(PUTIO_RECEIVER_APPLICATION_ID, selection.want(PUTIO_RECEIVER_APPLICATION_ID, sessionLive = false))
    }

    @Test
    fun theSentLoadFitsOneCastMessageWhenAndroidEscapesTheCustomData() {
        val streams = List(2_000) { PutioMediaStream(codecType = "subtitle", codecName = "a/b/c/d/e/f/g/h/$it") }
        val info = PutioMediaInfo(
            mimeType = "video/x-matroska",
            format = PutioMediaFormat(name = "matroska,webm", duration = 60.0, bitRate = 1_000L),
            streams = streams,
        )
        val media = Media.copy(receiverLoad = PlaybackCastReceiverLoad(fileId = 42L, token = TOKEN, mediaInfo = info))

        val request = media.loadRequest(12_000L, autoplay = true, speed = 1f, putioReceiver = true).toJson()

        assertTrue(request.toString().toByteArray().size <= 64 * 1024 - 1024)
        assertTrue(request.getJSONObject("customData").getJSONObject("media_info").getJSONArray("streams").length() > 0)
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

    @Test
    fun signOutWhileUnboundClearsStoredPutioReceiver() {
        CastReceiverStore.save(app, true)
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        MobileCast.playServices = { true }
        MobileCast.createGateway = { gateway }

        MobileCast.sessionLeft(app, session = null)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(CastReceiverStore.putio(app))
        assertEquals(DEFAULT, MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId)
    }

    @Test
    fun signOutWhileConnectedClearsPutioAndFreshProcessEndsResumedPutioSession() {
        CastReceiverStore.save(app, true)
        val firstCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTED,
        )
        val firstGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        firstGateway.bind(firstCast)
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { firstGateway }

        MobileCast.sessionLeft(app, session = null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, firstCast.endedSessions)
        assertFalse(CastReceiverStore.putio(app))
        assertEquals(
            PUTIO_RECEIVER_APPLICATION_ID,
            MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId,
        )

        MobileCast.resetForTests()

        val freshCast = FakeCastContextHandle(
            initialReceiver = MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId,
            castState = CastState.CONNECTING,
        )
        val freshGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        freshGateway.bind(freshCast)
        freshGateway.sessionEvents.onResuming()

        assertEquals(2, freshCast.endedSessions)

        freshCast.castState = CastState.NOT_CONNECTED
        freshGateway.sessionEvents.onEnded()

        assertEquals(listOf(DEFAULT), freshCast.applied)
        assertEquals(DEFAULT, MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId)
    }

    @Test
    fun signOutWhileIdleSwitchesToDefaultAndClearsStore() {
        CastReceiverStore.save(app, true)
        val cast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.NOT_CONNECTED,
        )
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        gateway.bind(cast)
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { gateway }

        MobileCast.sessionLeft(app, session = null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(DEFAULT), cast.applied)
        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))
        assertEquals(DEFAULT, MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId)
    }

    @Test
    fun freshProcessClearsPendingSignOutEndWhenNoSessionResumesWithinGraceWindow() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        var scheduled: Runnable? = null
        val freshCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.NOT_CONNECTED,
        )
        val freshGateway = GmsCastGateway(
            context = app,
            routes = GmsCastRoutes(app),
            remote = GmsCastRemote(),
            schedulePendingSignOutTimeout = { task ->
                scheduled = task
                Closeable { if (scheduled === task) scheduled = null }
            },
        ) {
            TaskCompletionSource<CastContext>().task
        }

        freshGateway.bind(freshCast)
        assertEquals(emptyList<String>(), freshCast.applied)
        assertEquals(CastReceiverStoredState.PendingSignOutEnd, CastReceiverStore.state(app))

        requireNotNull(scheduled).run()

        assertEquals(listOf(DEFAULT), freshCast.applied)
        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))
        assertEquals(DEFAULT, MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId)
    }

    @Test
    fun signOutWhileConnectedOnDefaultReceiverClearsStoreWithoutPendingEnd() {
        CastReceiverStore.save(app, true)
        val cast = FakeCastContextHandle(
            initialReceiver = DEFAULT,
            castState = CastState.CONNECTED,
        )
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        gateway.bind(cast)
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { gateway }

        MobileCast.sessionLeft(app, session = null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, cast.endedSessions)
        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))
        assertEquals(DEFAULT, MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId)
    }

    @Test
    fun signOutWhileConnectedClearsPendingStateOnceSessionEndsInSameProcess() {
        CastReceiverStore.save(app, true)
        val cast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTED,
        )
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        gateway.bind(cast)
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { gateway }

        MobileCast.sessionLeft(app, session = null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CastReceiverStoredState.PendingSignOutEnd, CastReceiverStore.state(app))

        cast.castState = CastState.NOT_CONNECTED
        gateway.sessionEvents.onEnded()

        assertEquals(listOf(DEFAULT), cast.applied)
        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))
    }

    @Test
    fun newAccountEnablingPutioDuringPendingSignOutStillEndsOrphanedSessionAndThenSavesPutio() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        val freshCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTING,
        )
        val freshGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        freshGateway.bind(freshCast)
        freshGateway.receivers.use(putio = true)
        freshGateway.sessionEvents.onResuming()

        assertEquals(2, freshCast.endedSessions)
        assertEquals(CastReceiverStoredState.PendingSignOutEnd, CastReceiverStore.state(app))

        freshCast.castState = CastState.NOT_CONNECTED
        freshGateway.sessionEvents.onEnded()

        assertEquals(emptyList<String>(), freshCast.applied)
        assertEquals(CastReceiverStoredState.Putio, CastReceiverStore.state(app))
    }

    @Test
    fun backgroundSessionLeftMarksPendingBeforeMainThreadRuns() {
        CastReceiverStore.save(app, true)
        val cast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTED,
        )
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        gateway.bind(cast)
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { gateway }
        MobileCast.controller(app)

        val worker = Thread { MobileCast.sessionLeft(app, session = null) }
        worker.start()
        worker.join()

        assertEquals(CastReceiverStoredState.PendingSignOutEnd, CastReceiverStore.state(app))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, cast.endedSessions)
    }

    @Test
    fun lateSessionResumeAfterGraceTimeoutStillEndsWithoutAttaching() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        var scheduled: Runnable? = null
        val freshCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.NOT_CONNECTED,
        )
        val freshGateway = GmsCastGateway(
            context = app,
            routes = GmsCastRoutes(app),
            remote = GmsCastRemote(),
            schedulePendingSignOutTimeout = { task ->
                scheduled = task
                Closeable { if (scheduled === task) scheduled = null }
            },
        ) {
            TaskCompletionSource<CastContext>().task
        }

        freshGateway.bind(freshCast)
        requireNotNull(scheduled).run()
        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))

        freshGateway.sessionEvents.onResuming()
        assertEquals(1, freshCast.endedSessions)
    }

    @Test
    fun failedCastContextLoadPreservesPendingSignOutEnd() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        val load = TaskCompletionSource<CastContext>()
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) { load.task }

        gateway.start()
        load.setException(IllegalStateException("Play services busy"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CastReceiverStoredState.PendingSignOutEnd, CastReceiverStore.state(app))
    }

    @Test
    fun signOutDuringConnectingEndsInFlightStartedSession() {
        val calls = mutableListOf<String>()
        val signOut = CastSignOutEnd { calls += "end" }

        signOut.requested(connecting = true)
        signOut.started { calls += "attach" }

        assertEquals(listOf("end", "end"), calls)
        assertTrue(signOut.isPending)
    }

    @Test
    fun newAccountSelectingPutioBeforeBindSurvivesOrphanedSessionTeardown() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        val freshCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTING,
        )
        val freshGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }

        freshGateway.receivers.use(putio = true)
        freshGateway.bind(freshCast)
        freshGateway.sessionEvents.onResuming()
        assertEquals(2, freshCast.endedSessions)

        freshCast.castState = CastState.NOT_CONNECTED
        freshGateway.sessionEvents.onEnded()

        assertEquals(emptyList<String>(), freshCast.applied)
        assertEquals(CastReceiverStoredState.Putio, CastReceiverStore.state(app))
    }

    @Test
    fun refusedDefaultSwitchRetriesWhenCastStateBecomesIdle() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        val freshCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTED,
            refuseNextSwitch = true,
        )
        val freshGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }

        freshGateway.bind(freshCast)
        freshCast.castState = CastState.NOT_CONNECTED
        freshGateway.sessionEvents.onEnded()

        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))
        assertEquals(PUTIO_RECEIVER_APPLICATION_ID, freshCast.receiverApplicationId)

        freshCast.notifyCastStateChanged()

        assertEquals(listOf(DEFAULT), freshCast.applied)
        assertEquals(DEFAULT, freshCast.receiverApplicationId)
    }

    @Test
    fun signedOutColdLaunchSessionLeftBeforeBindPreservesPendingSignOutEnd() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        val freshGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { freshGateway }

        MobileCast.sessionLeft(app, session = null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CastReceiverStoredState.PendingSignOutEnd, CastReceiverStore.state(app))
        assertEquals(
            PUTIO_RECEIVER_APPLICATION_ID,
            MobileCastOptionsProvider().getCastOptions(app).receiverApplicationId,
        )
    }

    @Test
    fun signOutBeforeBindConnectingStillEndsSessionOnBind() {
        CastReceiverStore.save(app, CastReceiverStoredState.PendingSignOutEnd)
        val freshCast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.CONNECTING,
        )
        val freshGateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }

        freshGateway.bind(freshCast)

        assertEquals(1, freshCast.endedSessions)
    }

    @Test
    fun backgroundSessionLeftWithIdleControllerClearsToDefaultImmediately() {
        CastReceiverStore.save(app, true)
        val cast = FakeCastContextHandle(
            initialReceiver = PUTIO_RECEIVER_APPLICATION_ID,
            castState = CastState.NOT_CONNECTED,
        )
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        gateway.bind(cast)
        MobileCast.playServices = { true }
        MobileCast.connectAudioSession = { Closeable {} }
        MobileCast.createGateway = { gateway }
        MobileCast.controller(app)

        val worker = Thread { MobileCast.sessionLeft(app, session = null) }
        worker.start()
        worker.join()

        assertEquals(CastReceiverStoredState.Default, CastReceiverStore.state(app))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(DEFAULT), cast.applied)
    }

    private class FakeCastContextHandle(
        initialReceiver: String = DEFAULT,
        override var castState: Int = CastState.NOT_CONNECTED,
        private var refuseNextSwitch: Boolean = false,
    ) : CastContextHandle {
        override val mergedSelector: MediaRouteSelector? = null
        override val currentCastSession: CastSession? = null
        private var current = initialReceiver
        override val receiverApplicationId: String get() = current
        val applied = mutableListOf<String>()
        var endedSessions = 0
            private set
        private var castStateListener: (() -> Unit)? = null

        override fun setReceiverApplicationId(applicationId: String) {
            if (refuseNextSwitch) {
                refuseNextSwitch = false
                error("Cast busy")
            }
            applied += applicationId
            current = applicationId
        }

        override fun addCastStateListener(listener: () -> Unit) {
            castStateListener = listener
        }

        override fun addSessionManagerListener(listener: SessionManagerListener<CastSession>) = Unit

        override fun addSessionTransferCallback(callback: SessionTransferCallback) = Unit

        override fun endCurrentSession(stopCasting: Boolean) {
            endedSessions++
        }

        fun notifyCastStateChanged() {
            castStateListener?.invoke()
        }
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
