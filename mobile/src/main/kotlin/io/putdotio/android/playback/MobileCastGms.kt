package io.putdotio.android.playback

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice as GmsCastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import io.putdotio.android.MainActivity
import io.putdotio.android.MobilePlaybackService
import io.putdotio.android.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The process's Cast controller, started on first use; null where Google Play services is missing.
 * MainActivity starts it in onCreate: Cast discovers receivers only for activities that resume after
 * it started.
 */
internal object MobileCast {
    @Volatile
    private var controller: MobileCastController? = null

    /** Google Play services, which runs Cast in its own process. */
    @VisibleForTesting
    var playServices: (Context) -> Boolean = ::playServicesAvailable

    fun controller(context: Context): MobileCastController? {
        val appContext = context.applicationContext
        if (!playServices(appContext)) return null
        return controller ?: synchronized(this) {
            controller ?: run {
                val gateway = GmsCastGateway(appContext).also { it.start() }
                MobileCastController(gateway, MainScope(), SystemClock::elapsedRealtime).also { cast ->
                    // The receiver's positions reach put.io as the local player's do, under the item's lease.
                    DefaultMobilePlayerFactory.observePositions(appContext, cast.remotePlayer)
                    controller = cast
                }
            }
        }
    }

    /**
     * The signed-in session left: the receiver plays with the account's download token, so the Cast
     * session ends with it, a session the Cast SDK resumes for a fresh process included.
     */
    fun sessionLeft(context: Context) {
        Handler(Looper.getMainLooper()).post { controller(context)?.end() }
    }

    /**
     * The file a session plays now, for the Cast notification's tap; null without one. A process the
     * tap just started resumes the session first, so this waits a moment for the receiver's word.
     */
    suspend fun castingTarget(context: Context): PlaybackTarget? {
        val cast = controller(context) ?: return null
        return withTimeoutOrNull(SESSION_RESUME_WAIT_MILLIS) {
            combine(cast.availability, cast.status) { _, _ -> cast.castingTarget() }.filterNotNull().first()
        }
    }

    private const val SESSION_RESUME_WAIT_MILLIS = 3_000L

    // A device without Play services, or one whose Play services cannot answer, has no Cast.
    @Suppress("SwallowedException")
    private fun playServicesAvailable(context: Context): Boolean =
        try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (_: RuntimeException) {
            false
        }
}

/**
 * Google's Default Media Receiver, as putio-web and putio-ios use it: put.io has no receiver of its
 * own. While casting, the Cast SDK's own notification and media session carry the remote controls;
 * the local player has stopped, so there is one of each.
 */
class MobileCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setActions(
                listOf(
                    MediaIntentReceiver.ACTION_REWIND,
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    MediaIntentReceiver.ACTION_FORWARD,
                    MediaIntentReceiver.ACTION_STOP_CASTING,
                ),
                // The compact notification keeps play and pause, and stop casting.
                intArrayOf(TOGGLE_ACTION_INDEX, STOP_ACTION_INDEX),
            )
            .setSkipStepMs(NOTIFICATION_SKIP_MILLIS)
            .setSmallIconDrawableResId(R.drawable.ic_ph_screencast)
            .setTargetActivityClassName(MobileCastNotificationActivity::class.java.name)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(notification)
                    .setMediaSessionEnabled(true)
                    .build(),
            )
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val NOTIFICATION_SKIP_MILLIS = 10_000L
        const val TOGGLE_ACTION_INDEX = 1
        const val STOP_ACTION_INDEX = 3
    }
}

/**
 * The Cast notification's tap. The Cast SDK opens its target with no action of its own, so this asks
 * the app for the playing file as the audio notification does, which opens the receiver's controls.
 */
class MobileCastNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

/**
 * [CastGateway] over the Cast SDK and MediaRouter. Without Google Play services, or when Cast fails
 * to start, it stays [CastAvailability.Unsupported] and touches nothing else.
 */
internal class GmsCastGateway private constructor(
    private val context: Context,
    private val routes: GmsCastRoutes,
    private val remote: GmsCastRemote,
) : CastGateway, CastRoutes by routes, CastRemote by remote {
    constructor(context: Context) : this(context, GmsCastRoutes(context), GmsCastRemote())

    init {
        remote.onAttachedChanged = { updateAvailability() }
    }

    private val mutableAvailability = MutableStateFlow<CastAvailability>(CastAvailability.Unsupported)
    override val availability: StateFlow<CastAvailability> = mutableAvailability
    private var castContext: CastContext? = null

    /**
     * Starts Cast at once: it tracks the activities that resume from here on and discovers receivers
     * only while one is resumed, so a start that lands after the activity resumed finds none. Play
     * services runs Cast in its own process; a failure to start it means no Cast here.
     */
    @Suppress("SwallowedException")
    fun start() {
        try {
            bind(CastContext.getSharedInstance(context))
        } catch (_: RuntimeException) {
            mutableAvailability.value = CastAvailability.Unsupported
        }
    }

    private fun bind(cast: CastContext) {
        castContext = cast
        if (stopOnBind) cast.sessionManager.endCurrentSession(true)
        routes.selector = cast.mergedSelector
        cast.addCastStateListener { updateAvailability() }
        cast.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
        updateAvailability()
    }

    /**
     * Connected spans exactly the time a media client is attached: a screen that sees it can load at
     * once, and the receiver's last status is out before a screen sees the session gone.
     */
    private fun updateAvailability() {
        val cast = castContext ?: return
        val device = cast.sessionManager.currentCastSession?.castDevice?.friendlyName
        mutableAvailability.value = when {
            remote.attached -> CastAvailability.Connected(device.orEmpty())
            cast.castState == CastState.NO_DEVICES_AVAILABLE ->
                if (localNetworkBlocked()) CastAvailability.NeedsLocalNetwork else CastAvailability.NoDevices
            cast.castState == CastState.CONNECTING || cast.castState == CastState.CONNECTED ->
                CastAvailability.Connecting(device)
            else -> CastAvailability.Available
        }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = updateAvailability()

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            // A new session is not the one a sign-out ended.
            remote.discardHandback = false
            attach(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) = updateAvailability()

        // The receiver's last position, read while the session still answers.
        override fun onSessionEnding(session: CastSession) = remote.publish()

        override fun onSessionEnded(session: CastSession, error: Int) = detach()

        override fun onSessionResuming(session: CastSession, sessionId: String) = updateAvailability()

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)

        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()

        override fun onSessionSuspended(session: CastSession, reason: Int) = remote.publish()
    }

    private fun attach(session: CastSession) {
        remote.attach(session.remoteMediaClient)
        updateAvailability()
    }

    private fun detach() {
        remote.detach()
        updateAvailability()
    }

    /** Ends the session; before Cast has started, it ends the session Cast resumes once it does. */
    override fun endForSignOut() {
        remote.discardHandback = true
        stopCasting()
    }

    override fun stopCasting() {
        val cast = castContext
        if (cast == null) stopOnBind = true else cast.sessionManager.endCurrentSession(true)
    }

    private var stopOnBind = false

    override fun localNetworkAnswered(granted: Boolean) {
        if (!granted) localNetworkRefused = true
        updateAvailability()
    }

    private var localNetworkRefused = false

    private fun localNetworkBlocked(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && !localNetworkRefused &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
}

/** The receivers MediaRouter finds for the Cast framework's selector, while the picker asks. */
internal class GmsCastRoutes(private val context: Context) : CastRoutes {
    private val mutableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices: StateFlow<List<CastDevice>> = mutableDevices
    var selector: MediaRouteSelector? = null
    private var discovering = false

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
    }

    override fun startDiscovery() {
        val selector = selector ?: return
        if (discovering) return
        discovering = true
        MediaRouter.getInstance(context).addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    override fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        MediaRouter.getInstance(context).removeCallback(callback)
    }

    private fun refresh() {
        val selector = selector ?: return
        mutableDevices.value = MediaRouter.getInstance(context).routes
            .filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { route ->
                val device = GmsCastDevice.getFromBundle(route.extras)
                val audioOnly = device?.hasCapability(GmsCastDevice.CAPABILITY_VIDEO_OUT) == false
                CastDevice(
                    id = route.id,
                    name = route.name,
                    model = device?.modelName?.takeIf { it.isNotBlank() },
                    audioOnly = audioOnly,
                    kind = when {
                        audioOnly -> CastDeviceKind.Speaker
                        route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV -> CastDeviceKind.Tv
                        else -> CastDeviceKind.Other
                    },
                    selected = route.isSelected,
                )
            }
    }

    override fun select(deviceId: String) {
        val router = MediaRouter.getInstance(context)
        router.routes.firstOrNull { it.id == deviceId }?.let(router::selectRoute)
    }
}

/** The session's media client: what the receiver plays, and the commands that drive it. */
internal class GmsCastRemote : CastRemote {
    private val mutableStatus = MutableStateFlow<CastRemoteStatus?>(null)
    override val status: StateFlow<CastRemoteStatus?> = mutableStatus
    override var endedStatus: CastRemoteStatus? = null
        private set
    private var client: RemoteMediaClient? = null
    private var lastFileId: Long? = null

    /** The latest load request, so only its own result settles it; an older one for the same file does not. */
    private var loadRequest = 0L
    private var loadSettled = true

    /** A refused load, which stands over the receiver's reports until the next load. */
    private var failedLoad: CastRemoteStatus? = null

    /** The latest load's playlist subtitle, until the receiver lists the stream's text tracks. */
    private var pendingSubtitle: Pair<Long, PlaybackCastPlaylistSubtitle>? = null

    /** Whether play was last asked for, which a loading receiver's status does not say. */
    private var playIntent: Boolean? = null

    /**
     * Whether the receiver has said what it plays. A resumed session can attach before its first
     * status, and a screen must not take "nothing yet" for "nothing", or it would load over the
     * receiver's own position.
     */
    private var statusKnown = false

    /** Set when signing out ends the session: its end hands nothing back. */
    var discardHandback = false

    /** Runs when [attached] changes, so availability follows it. */
    var onAttachedChanged: () -> Unit = {}

    val attached: Boolean get() = client != null && statusKnown

    private val markStatusKnown = {
        if (client != null && !statusKnown) {
            statusKnown = true
            onAttachedChanged()
        }
    }

    /** A receiver that answered with no media plays nothing, whatever it played before. */
    private val clearIfEmpty = {
        if (client?.mediaStatus == null) mutableStatus.value = null
    }

    /**
     * Sends the latest load's playlist subtitle once that load has answered: before then the
     * receiver's status still describes the media before it, which can be the same file.
     */
    private val showPendingSubtitle = {
        val remote = client
        if (loadSettled && remote != null) pendingSubtitle = pendingSubtitle?.let(remote::showPlaylistSubtitle)
    }

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            clearIfEmpty()
            markStatusKnown()
            publish()
            showPendingSubtitle()
        }

        override fun onMetadataUpdated() = publish()
    }

    private val progress = RemoteMediaClient.ProgressListener { _, _ -> publish() }

    fun attach(remote: RemoteMediaClient?) {
        release()
        client = remote?.also {
            it.registerCallback(callback)
            it.addProgressListener(progress, PROGRESS_INTERVAL_MILLIS)
            // An answered first status request settles whether the receiver plays anything; without an
            // answer the next status update does.
            it.requestStatus().setResultCallback { result ->
                if (client === it && result.status.isSuccess) {
                    clearIfEmpty()
                    publish()
                    markStatusKnown()
                }
            }
        }
        publish()
    }

    /** The session ended: one last status, then none, so the app hands the file back. */
    fun detach() {
        publish()
        release()
        lastFileId = null
        // A load still in flight belongs to the session that ended.
        loadRequest++
        failedLoad = null
        pendingSubtitle = null
        playIntent = null
        endedStatus = mutableStatus.value?.takeUnless { discardHandback }
            ?.copy(endedAtMillis = SystemClock.elapsedRealtime())
        discardHandback = false
        mutableStatus.value = null
    }

    private fun release() {
        client?.let {
            it.unregisterCallback(callback)
            it.removeProgressListener(progress)
        }
        client = null
        statusKnown = false
    }

    fun publish() {
        val remote = client ?: return
        val media = remote.mediaStatus ?: return
        // A finished file's status drops its media; it still belongs to the file it finished, with the
        // title and kind it was sent with. Media another sender sent, untagged, belongs to no file.
        val info = remote.mediaInfo
        val fileId = info?.customData?.optLong(FILE_ID_KEY)?.takeIf { it > 0L }
            ?: lastFileId.takeIf { info == null && media.playerState == MediaStatus.PLAYER_STATE_IDLE }
        val previous = mutableStatus.value?.takeIf { info == null && it.fileId == fileId }
        val state = media.castRemoteState()
        noteReport(fileId, state)
        mutableStatus.value = failedLoad ?: CastRemoteStatus(
            fileId = fileId,
            state = state,
            positionMillis = remote.approximateStreamPosition.coerceAtLeast(0L),
            durationMillis = remote.streamDuration.takeIf { it > 0L },
            speed = media.playbackRate.toFloat().takeIf { it > 0f } ?: 1f,
            speedSupported = media.isMediaCommandSupported(MediaStatus.COMMAND_PLAYBACK_RATE),
            seekable = media.isMediaCommandSupported(MediaStatus.COMMAND_SEEK),
            sampledAtMillis = SystemClock.elapsedRealtime(),
            playIntent = playIntent,
            title = info?.metadata?.getString(MediaMetadata.KEY_TITLE) ?: previous?.title,
            audio = previous?.audio ?: (info?.metadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC_TRACK),
        )
    }

    /**
     * The receiver reported [state] for [fileId]. A report never settles a load: one for the same file
     * sent again can be about the media before it, so only the load's own result does.
     */
    private fun noteReport(fileId: Long?, state: CastRemoteState) {
        lastFileId = fileId
        // A load's own intent stands until its result: reports on the media before it say nothing of it.
        if (loadSettled && (state == CastRemoteState.Playing || state == CastRemoteState.Paused)) {
            playIntent = state == CastRemoteState.Playing
        }
    }

    override fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float) {
        val remote = client ?: return
        lastFileId = media.fileId
        val request = ++loadRequest
        loadSettled = false
        failedLoad = null
        pendingSubtitle = media.playlistSubtitle?.let { media.fileId to it }
        playIntent = autoplay
        remote.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(media.toMediaInfo())
                .setAutoplay(autoplay)
                .setCurrentTime(startMillis)
                .setPlaybackRate(speed.castRate())
                .setActiveTrackIds(media.activeSubtitleId?.let { longArrayOf(it) } ?: longArrayOf())
                .build(),
        ).setResultCallback { result ->
            // A refused or timed-out load says so for its file, unless another load followed it.
            if (loadRequest == request) loadSettled = true
            if (client === remote && loadRequest == request && result.status.isSuccess) showPendingSubtitle()
            if (client === remote && loadRequest == request && !result.status.isSuccess) {
                failedLoad = CastRemoteStatus(
                    fileId = media.fileId,
                    state = CastRemoteState.Failed,
                    positionMillis = startMillis,
                    durationMillis = media.durationMillis,
                    sampledAtMillis = SystemClock.elapsedRealtime(),
                    title = media.title,
                    audio = media.mediaType == PlaybackMediaType.AUDIO,
                )
                mutableStatus.value = failedLoad
            }
        }
    }

    override fun play() {
        playIntent = true
        client?.play()
    }

    override fun pause() {
        playIntent = false
        client?.pause()
    }

    override fun seekTo(positionMillis: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(positionMillis).build())
    }

    override fun setSpeed(speed: Float) {
        client?.setPlaybackRate(speed.castRate())
    }
}

/** Cast takes 0.5x to 2x; the player's faster presets play at 2x there. */
private fun Float.castRate(): Double =
    toDouble().coerceIn(MediaLoadRequestData.PLAYBACK_RATE_MIN, MediaLoadRequestData.PLAYBACK_RATE_MAX)

private fun MediaStatus.castRemoteState(): CastRemoteState =
    when (playerState) {
        MediaStatus.PLAYER_STATE_PLAYING -> CastRemoteState.Playing
        MediaStatus.PLAYER_STATE_PAUSED -> CastRemoteState.Paused
        MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> CastRemoteState.Buffering
        MediaStatus.PLAYER_STATE_IDLE -> when (idleReason) {
            MediaStatus.IDLE_REASON_FINISHED -> CastRemoteState.Ended
            MediaStatus.IDLE_REASON_ERROR -> CastRemoteState.Failed
            else -> CastRemoteState.Idle
        }
        else -> CastRemoteState.Idle
    }

/** The Cast form of [PlaybackCastMedia], tagged with its file id. */
private fun PlaybackCastMedia.toMediaInfo(): MediaInfo {
    val kind = if (mediaType == PlaybackMediaType.AUDIO) {
        MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
    } else {
        MediaMetadata.MEDIA_TYPE_MOVIE
    }
    val metadata = MediaMetadata(kind).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        posterUrl?.let { addImage(WebImage(it.toUri())) }
    }
    return MediaInfo.Builder(fileId.toString())
        .setContentUrl(url)
        .setContentType(contentType)
        .setStreamType(if (buffered) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_NONE)
        .setMetadata(metadata)
        .setMediaTracks(
            subtitles.map { subtitle ->
                MediaTrack.Builder(subtitle.id, MediaTrack.TYPE_TEXT)
                    .setContentId(subtitle.url)
                    .setContentType(WEBVTT_CONTENT_TYPE)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setName(subtitle.name)
                    .setLanguage(subtitle.languageCode)
                    .build()
            },
        )
        .setCustomData(JSONObject().put(FILE_ID_KEY, fileId))
        .apply { durationMillis?.let(::setStreamDuration) }
        .build()
}

/**
 * Turns on the playlist subtitle a load asked for, once the receiver lists the text tracks of that
 * file's stream: an HLS playlist's own tracks exist only after it loads. Returns what still waits.
 */
private fun RemoteMediaClient.showPlaylistSubtitle(
    pending: Pair<Long, PlaybackCastPlaylistSubtitle>,
): Pair<Long, PlaybackCastPlaylistSubtitle>? {
    val (fileId, wanted) = pending
    val tracks = mediaInfo?.takeIf { it.customData?.optLong(FILE_ID_KEY) == fileId }
        ?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_TEXT }
    if (tracks.isEmpty()) return pending
    playlistSubtitleTrack(tracks.map { CastTextTrack(it.id, it.language) }, wanted)
        ?.let { setActiveMediaTracks(longArrayOf(it)) }
    return null
}

/** Ties the receiver's media to the put.io file; another sender's media carries none. */
private const val FILE_ID_KEY = "putioFileId"
private const val WEBVTT_CONTENT_TYPE = "text/vtt"
private const val PROGRESS_INTERVAL_MILLIS = 1_000L
