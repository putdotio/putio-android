package io.putdotio.android.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as Media3Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.BackgroundExecutor
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice as GmsCastDevice
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.SessionState
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.SessionTransferCallback
import com.google.android.gms.cast.framework.SessionTransferCallback.TRANSFER_TYPE_FROM_REMOTE_TO_LOCAL
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.MobilePlaybackService
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The process's Cast controller, started on first use; null where Google Play services is missing.
 * The Cast framework loads off the main thread, so starting it costs the launch nothing.
 */
internal object MobileCast {
    @Volatile
    @VisibleForTesting
    internal var controller: MobileCastController? = null

    private val castSessionPlayers: MutableSet<Player> = Collections.newSetFromMap(IdentityHashMap())

    @VisibleForTesting
    internal var castScope: CoroutineScope = MainScope()
        private set

    /** Google Play services, which runs Cast in its own process. */
    @VisibleForTesting
    var playServices: (Context) -> Boolean = ::playServicesAvailable

    @VisibleForTesting
    internal var createGateway: (Context) -> CastGateway = { GmsCastGateway(it).also(GmsCastGateway::start) }

    @VisibleForTesting
    internal var onCastFinished: (Long) -> Unit = MobilePlaybackService.analytics::castFinished

    @VisibleForTesting
    internal var connectAudioSession: (Context) -> Closeable = { DefaultMobilePlayerFactory.connectAudio(it) {} }

    @VisibleForTesting
    internal var createRemotePlayer: (Context) -> Player = ::defaultRemoteCastPlayer

    @VisibleForTesting
    internal var createCastPlayer: (
        Context,
        Player,
        Player,
        (MediaItem, CastHandover) -> Unit,
        () -> Unit,
    ) -> Player = ::defaultCastPlayer

    fun controller(context: Context): MobileCastController? {
        val appContext = context.applicationContext
        if (!playServices(appContext)) return null
        return controller ?: synchronized(this) {
            controller ?: run {
                val gateway = createGateway(appContext)
                MobileCastController(gateway, castScope, SystemClock::elapsedRealtime).also { cast ->
                    // The receiver's positions reach put.io as the local player's do, under the item's lease.
                    DefaultMobilePlayerFactory.observePositions(appContext, cast.remotePlayer)
                    castScope.launch { cast.finished.collect { onCastFinished(it) } }
                    holdSessionWhileCasting(appContext, cast)
                    controller = cast
                }
            }
        }
    }

    /** Starts Cast for MainActivity, which looks for receivers itself while it shows. */
    fun follow(activity: ComponentActivity) {
        val gateway = controller(activity)?.gateway as? GmsCastGateway ?: return
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = gateway.shown(owner, true)

                override fun onStop(owner: LifecycleOwner) = gateway.shown(owner, false)

                override fun onDestroy(owner: LifecycleOwner) {
                    gateway.shown(owner, false)
                    owner.lifecycle.removeObserver(this)
                }
            },
        )
    }

    /**
     * The signed-in [session] left: the receiver plays with the account's download token, so the
     * Cast session ends with it, a session the Cast SDK resumes for a fresh process included.
     */
    fun sessionLeft(context: Context, session: Any?) {
        Handler(Looper.getMainLooper()).post {
            controller(context)?.let {
                it.end()
                it.autoplay.forget(session)
            }
            useReceiver(context, putio = false)
        }
    }

    /**
     * The file a session plays now, for the session notification's tap; null without one. A process
     * the tap just started resumes the session first, so this waits a moment for the receiver's word.
     */
    suspend fun castingTarget(context: Context): PlaybackTarget? {
        val cast = controller(context) ?: return null
        return withTimeoutOrNull(SESSION_RESUME_WAIT_MILLIS) {
            combine(cast.availability, cast.status) { _, _ -> cast.castingTarget() }.filterNotNull().first()
        }
    }

    fun sessionPlayer(context: Context, local: Player, scope: CoroutineScope, beforeHandover: () -> Unit): Player {
        val cast = controller(context) ?: return local
        return buildGuardedSessionPlayer(
            local = local,
            createRemote = { createRemotePlayer(context) },
            createCast = { remote ->
                createCastPlayer(
                    context,
                    local,
                    remote,
                    { item, handover ->
                        beforeHandover()
                        cast.castFromSession(item, handover, local, scope)
                    },
                    { cast.resumeHere(local, scope) },
                )
            },
        ).also { created ->
            if (created !== local) {
                synchronized(castSessionPlayers) { castSessionPlayers.add(created) }
            }
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(this) {
            castScope.cancel()
            castScope = MainScope()
            controller = null
            synchronized(castSessionPlayers) { castSessionPlayers.clear() }
            playServices = ::playServicesAvailable
            createGateway = { GmsCastGateway(it).also(GmsCastGateway::start) }
            onCastFinished = MobilePlaybackService.analytics::castFinished
            connectAudioSession = { DefaultMobilePlayerFactory.connectAudio(it) {} }
            createRemotePlayer = ::defaultRemoteCastPlayer
            createCastPlayer = ::defaultCastPlayer
        }
    }

    fun stopCasting(context: Context) {
        controller(context)?.gateway?.stopCasting()
    }

    fun releaseSessionPlayer(context: Context, player: Player) {
        val ownsCast = synchronized(castSessionPlayers) { castSessionPlayers.remove(player) }
        val cast = controller(context)?.takeIf { ownsCast } ?: return player.release()
        player.releaseOnceCastEnds(cast.availability, castScope)
    }

    private fun holdSessionWhileCasting(context: Context, cast: MobileCastController) {
        var held: Closeable? = null
        castScope.launch {
            try {
                cast.availability
                    .map { it.casting }
                    .distinctUntilChanged()
                    .collectLatest { casting ->
                        if (casting) {
                            held = held ?: connectAudioSession(context)
                        } else {
                            delay(MOVE_HERE_WAIT_MILLIS)
                            held?.closeQuietly()
                            held = null
                        }
                    }
            } finally {
                held?.closeQuietly()
                held = null
            }
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

// Missing or broken Play services Cast falls back to local playback.
@Suppress("SwallowedException")
internal fun <R : Player> buildGuardedSessionPlayer(
    local: Player,
    createRemote: () -> R,
    createCast: (R) -> Player,
): Player {
    var remote: R? = null
    var built = false
    return try {
        val created = createRemote().also { remote = it }
        createCast(created).also { built = true }
    } catch (_: RuntimeException) {
        local
    } finally {
        if (!built) runCatching { remote?.release() }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun defaultRemoteCastPlayer(context: Context): Player =
    RemoteCastPlayer.Builder(context.applicationContext)
        .setMediaItemConverter(PutioCastMediaItemConverter(context))
        .build()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun defaultCastPlayer(
    context: Context,
    local: Player,
    remote: Player,
    toReceiver: (MediaItem, CastHandover) -> Unit,
    toLocal: () -> Unit,
): Player {
    require(remote is RemoteCastPlayer) { "Expected RemoteCastPlayer" }
    return CastPlayer.Builder(context)
        .setLocalPlayer(local)
        .setRemotePlayer(remote)
        .setTransferCallback(CastTransfer(toReceiver = toReceiver, toLocal = toLocal))
        .build()
}

/**
 * Google's Default Media Receiver, or the put.io receiver once the account opts in; the gateway
 * switches between them as the account's choice changes. The audio session's CastPlayer carries the
 * remote controls, so the Cast SDK shows neither a notification nor a media session of its own. The
 * system's output switcher can move a session back to this device.
 */
class MobileCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(receiverApplicationId(CastReceiverStore.putio(context)))
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(null)
                    .setMediaSessionEnabled(false)
                    .build(),
            )
            .setStopReceiverApplicationWhenEndingSession(true)
            .setRemoteToLocalEnabled(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

@UnstableApi
internal class CastTransfer(
    private val toReceiver: (MediaItem, CastHandover) -> Unit,
    private val toLocal: () -> Unit,
) : CastPlayer.TransferCallback {
    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (targetPlayer.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return toLocal()
        val state = sourcePlayer.playbackState
        val item = sourcePlayer.currentMediaItem
            ?.takeIf { state != Player.STATE_IDLE && state != Player.STATE_ENDED } ?: return
        val handover = CastHandover(
            positionMillis = sourcePlayer.currentPosition.coerceAtLeast(0L),
            playing = sourcePlayer.playWhenReady,
            speed = sourcePlayer.playbackParameters.speed,
        )
        toReceiver(item, handover)
        sourcePlayer.pause()
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class CastStopControl(context: Context, private val stop: () -> Unit) {
    val command = SessionCommand(ACTION_STOP_CASTING, Bundle.EMPTY)

    private val button = CommandButton.Builder(CommandButton.ICON_STOP)
        .setDisplayName(context.getString(R.string.mobile_cast_stop))
        .setSessionCommand(command)
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()

    fun buttons(device: DeviceInfo): List<CommandButton> =
        if (device.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) listOf(button) else emptyList()

    fun handle(command: SessionCommand): Boolean {
        if (command.customAction != ACTION_STOP_CASTING) return false
        stop()
        return true
    }

    private companion object {
        const val ACTION_STOP_CASTING = "io.putdotio.android.action.STOP_CASTING"
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class CastStopCallback(private val castStop: CastStopControl) : MediaSession.Callback {
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
        val commands = defaults.availableSessionCommands.buildUpon().add(castStop.command).build()
        return Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build(),
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> =
        if (castStop.handle(customCommand)) {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else {
            super.onCustomCommand(session, controller, customCommand, args)
        }
}

@UnstableApi
internal class PutioCastMediaItemConverter(context: Context) : MediaItemConverter {
    private val appName = context.getString(DesignR.string.app_name)

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val media = mediaItem.localConfiguration?.tag as? PlaybackCastMedia
        val info = media?.toMediaInfo() ?: MediaInfo.Builder(mediaItem.mediaId).build()
        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val metadata = info?.metadata
        val fileId = info?.customData?.optLong(FILE_ID_KEY)?.takeIf { it > 0L }
        return MediaItem.Builder()
            .setMediaId(fileId?.toString() ?: MediaItem.DEFAULT_MEDIA_ID)
            .setMediaMetadata(
                Media3Metadata.Builder()
                    .setTitle(metadata?.getString(MediaMetadata.KEY_TITLE))
                    .setArtist(metadata?.getString(MediaMetadata.KEY_ARTIST) ?: appName)
                    .setArtworkUri(metadata?.images?.firstOrNull()?.url)
                    .setMediaType(
                        if (metadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC_TRACK) {
                            Media3Metadata.MEDIA_TYPE_MUSIC
                        } else {
                            Media3Metadata.MEDIA_TYPE_MOVIE
                        },
                    )
                    .build(),
            )
            .build()
    }
}

internal class CastSignOutEnd(private val end: () -> Unit) {
    private var pending = false

    fun requested() {
        pending = true
        end()
    }

    fun resumed(attach: () -> Unit) = if (pending) end() else attach()

    fun sessionStarted() {
        pending = false
    }

    fun sessionEnded() {
        pending = false
    }
}

internal interface CastContextHandle {
    val mergedSelector: MediaRouteSelector?
    val castState: Int
    val currentCastSession: CastSession?
    val receiverApplicationId: String

    fun setReceiverApplicationId(applicationId: String)

    fun addCastStateListener(listener: () -> Unit)

    fun addSessionManagerListener(listener: SessionManagerListener<CastSession>)

    fun addSessionTransferCallback(callback: SessionTransferCallback)

    fun endCurrentSession(stopCasting: Boolean)
}

private class GmsCastContextHandle(private val cast: CastContext) : CastContextHandle {
    override val mergedSelector: MediaRouteSelector?
        get() = cast.mergedSelector

    override val castState: Int
        get() = cast.castState

    override val currentCastSession: CastSession?
        get() = cast.sessionManager.currentCastSession

    override val receiverApplicationId: String
        get() = cast.castOptions.receiverApplicationId

    override fun setReceiverApplicationId(applicationId: String) = cast.setReceiverApplicationId(applicationId)

    override fun addCastStateListener(listener: () -> Unit) = cast.addCastStateListener { listener() }

    override fun addSessionManagerListener(listener: SessionManagerListener<CastSession>) =
        cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java)

    override fun addSessionTransferCallback(callback: SessionTransferCallback) =
        cast.addSessionTransferCallback(callback)

    override fun endCurrentSession(stopCasting: Boolean) = cast.sessionManager.endCurrentSession(stopCasting)
}

/**
 * [CastGateway] over the Cast SDK and MediaRouter. Without Google Play services, or when Cast fails
 * to start, it stays [CastAvailability.Unsupported] and touches nothing else.
 */
internal class GmsCastGateway @VisibleForTesting internal constructor(
    private val context: Context,
    private val routes: GmsCastRoutes,
    private val remote: GmsCastRemote,
    private val localNetworkDenied: () -> Boolean = { context.localNetworkNotGranted() },
    private val loadCast: (Context) -> Task<CastContext>,
) : CastGateway, CastRoutes by routes, CastRemote by remote {
    constructor(context: Context) : this(context, GmsCastRoutes(context), GmsCastRemote(), loadCast = ::loadCastContext)

    init {
        remote.onAttachedChanged = { updateAvailability() }
        routes.onDevicesChanged = { updateAvailability() }
    }

    private val mutableAvailability = MutableStateFlow<CastAvailability>(CastAvailability.Unsupported)
    override val availability: StateFlow<CastAvailability> = mutableAvailability
    private val mutableMovesHere = MutableStateFlow(false)
    override val movesHere: StateFlow<Boolean> = mutableMovesHere
    private var castContext: CastContextHandle? = null
    private val signOut = CastSignOutEnd { castContext?.endCurrentSession(true) }
    private val startedOwners: MutableSet<LifecycleOwner> = Collections.newSetFromMap(IdentityHashMap())
    val receivers = CastReceiverSwitch(context, routes::reselect)
    private val messages = PutioReceiverMessages(remote::receiverReported)

    /**
     * Loads Cast off the main thread and binds it once it answers. Play services runs Cast in its own
     * process; a failure to start it means no Cast here.
     */
    @Suppress("SwallowedException")
    fun start() {
        val load = try {
            loadCast(context)
        } catch (_: RuntimeException) {
            return
        }
        load.addOnCompleteListener(ContextCompat.getMainExecutor(context)) { result ->
            val cast = result.takeIf { it.isSuccessful }?.result ?: return@addOnCompleteListener
            try {
                bind(GmsCastContextHandle(cast))
            } catch (_: RuntimeException) {
                castContext = null
                mutableAvailability.value = CastAvailability.Unsupported
            }
        }
    }

    @VisibleForTesting
    internal fun bind(cast: CastContextHandle) {
        castContext = cast
        routes.selector = cast.mergedSelector
        receivers.bind(cast)
        cast.addCastStateListener { updateAvailability() }
        cast.addSessionManagerListener(sessions)
        cast.addSessionTransferCallback(transfers)
        cast.currentCastSession?.let { current ->
            signOut.resumed { if (current.isConnected) attach(current) }
        }
        if (startedOwners.isNotEmpty()) routes.watch()
        updateAvailability()
    }

    /**
     * Connected spans exactly the time a media client is attached: a screen that sees it can load at
     * once, and the receiver's last status is out before a screen sees the session gone.
     */
    private fun updateAvailability() {
        val cast = castContext ?: return
        val device = cast.currentCastSession?.castDevice?.friendlyName
        mutableAvailability.value = when {
            remote.attached -> CastAvailability.Connected(device.orEmpty())
            cast.castState == CastState.NO_DEVICES_AVAILABLE && routes.devices.value.isEmpty() ->
                if (localNetworkBlocked()) CastAvailability.NeedsLocalNetwork else CastAvailability.NoDevices
            cast.castState == CastState.CONNECTING || cast.castState == CastState.CONNECTED ->
                CastAvailability.Connecting(device)
            else -> CastAvailability.Available
        }
    }

    private val transfers = object : SessionTransferCallback() {
        override fun onTransferring(transferType: Int) {
            if (transferType == TRANSFER_TYPE_FROM_REMOTE_TO_LOCAL) mutableMovesHere.value = true
        }

        override fun onTransferred(transferType: Int, sessionState: SessionState) =
            onTransferring(transferType)

        override fun onTransferFailed(transferType: Int, reason: Int) {
            if (transferType == TRANSFER_TYPE_FROM_REMOTE_TO_LOCAL) mutableMovesHere.value = false
        }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            mutableMovesHere.value = false
            updateAvailability()
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            // A new session is not the one a sign-out ended.
            signOut.sessionStarted()
            remote.discardHandback = false
            attach(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            updateAvailability()
            receivers.sessionGone()
        }

        // The receiver's last position, read while the session still answers.
        override fun onSessionEnding(session: CastSession) = remote.publish()

        override fun onSessionEnded(session: CastSession, error: Int) {
            signOut.sessionEnded()
            detach()
            receivers.sessionGone()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            mutableMovesHere.value = false
            signOut.resumed {}
            updateAvailability()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
            signOut.resumed { attach(session) }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            signOut.sessionEnded()
            detach()
            receivers.sessionGone()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = remote.publish()
    }

    private fun attach(session: CastSession) {
        val putio = session.applicationMetadata?.applicationId == PUTIO_RECEIVER_APPLICATION_ID
        remote.attach(session.remoteMediaClient, putio)
        messages.listen(session.takeIf { putio })
        updateAvailability()
    }

    private fun detach() {
        messages.listen(null)
        remote.detach()
        updateAvailability()
    }

    /** Ends the session, or, before Cast has resumed one, the session it resumes next. */
    override fun endForSignOut() {
        remote.discardHandback = true
        signOut.requested()
    }

    override fun stopCasting() {
        castContext?.endCurrentSession(true)
    }

    /**
     * Once the app has been in the background, as on the trip to the sign-in tab, Cast on some
     * phones never sees it return: it stops looking for receivers and reports none until the
     * process restarts. So the app looks for them itself while it shows, and a receiver it finds
     * offers Cast whatever Cast's own state says.
     */
    fun shown(owner: LifecycleOwner, shown: Boolean) {
        if (shown) {
            val becameVisible = startedOwners.isEmpty()
            if (startedOwners.add(owner) && becameVisible) {
                routes.watch()
                updateAvailability()
            }
        } else if (startedOwners.remove(owner) && startedOwners.isEmpty()) {
            routes.unwatch()
        }
    }

    override fun localNetworkAnswered() = updateAvailability()

    @VisibleForTesting
    internal fun localNetworkBlocked(): Boolean = localNetworkDenied()
}

private fun Context.localNetworkNotGranted(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
        PackageManager.PERMISSION_GRANTED

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun loadCastContext(context: Context): Task<CastContext> =
    CastContext.getSharedInstance(context, BackgroundExecutor.get())

/** The receivers MediaRouter finds for the Cast framework's selector, while the picker asks. */
internal class GmsCastRoutes(private val context: Context) : CastRoutes {
    private val mutableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices: StateFlow<List<CastDevice>> = mutableDevices
    var selector: MediaRouteSelector? = null
    var onDevicesChanged: () -> Unit = {}
    private var discovering = false
    private var watching = false

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
    }

    override fun startDiscovery() {
        if (selector == null || discovering) return
        discovering = true
        updateCallback()
    }

    override fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        updateCallback()
    }

    /** The passive search while the app shows; the picker's active scan rides on the same callback. */
    fun watch() {
        if (selector == null || watching) return
        watching = true
        updateCallback()
    }

    fun unwatch() {
        if (!watching) return
        watching = false
        updateCallback()
    }

    private fun updateCallback() {
        val selector = selector ?: return
        val router = MediaRouter.getInstance(context)
        // Adding a callback again only widens its flags, so the scan narrows by starting over.
        router.removeCallback(callback)
        when {
            discovering -> router.addCallback(
                selector.republishingReceivers(),
                callback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
            )
            watching -> router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        refresh()
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
        onDevicesChanged()
    }

    /** The Cast framework's selector after the receiver changed: the search starts over with it. */
    fun reselect(selector: MediaRouteSelector?) {
        if (selector == null || selector == this.selector) return
        this.selector = selector
        if (discovering || watching) updateCallback() else refresh()
    }

    private fun MediaRouteSelector.republishingReceivers(): MediaRouteSelector =
        MediaRouteSelector.Builder(this).addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK).build()

    override fun select(deviceId: String) {
        val router = MediaRouter.getInstance(context)
        router.routes.firstOrNull { it.id == deviceId }?.let(router::selectRoute)
    }
}

/** The session's media client: what the receiver plays, and the commands that drive it. */
// One function per RemoteMediaClient command or callback the app uses.
@Suppress("TooManyFunctions")
internal class GmsCastRemote : CastRemote {
    private val mutableStatus = MutableStateFlow<CastRemoteStatus?>(null)
    override val status: StateFlow<CastRemoteStatus?> = mutableStatus
    override var endedStatus: CastRemoteStatus? = null
        private set
    private var client: RemoteMediaClient? = null
    private var lastFileId: Long? = null

    private val latestLoad = CastLoad()

    /** The latest load's playlist subtitle, until the receiver lists the stream's text tracks. */
    private var pendingSubtitle: Pair<Long, PlaybackCastPlaylistSubtitle>? = null

    /** Whether the session runs the put.io receiver, which takes [PlaybackCastMedia.receiverLoad]. */
    private var putioReceiver = false

    /** The latest load and where it started, for a failure the put.io receiver reports later. */
    private var lastLoad: Pair<PlaybackCastMedia, Long>? = null

    /** The put.io receiver's word that the latest load's stream failed, which a resolved load can hide. */
    private var receiverFailure: CastRemoteStatus? = null

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
        if (!latestLoad.inFlight && remote != null) {
            pendingSubtitle = pendingSubtitle?.let { remote.showPlaylistSubtitle(it, putioReceiver) }
        }
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

    fun attach(remote: RemoteMediaClient?, putio: Boolean = false) {
        release()
        latestLoad.reset()
        putioReceiver = putio
        lastLoad = null
        receiverFailure = null
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
        latestLoad.reset()
        putioReceiver = false
        lastLoad = null
        receiverFailure = null
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
        val fileId = info?.putioFileId(putioReceiver)
            ?: lastFileId.takeIf { info == null && media.playerState == MediaStatus.PLAYER_STATE_IDLE }
        val previous = mutableStatus.value?.takeIf { info == null && it.fileId == fileId }
        val state = media.castRemoteState()
        noteReport(fileId, state)
        // Media another sender loaded since is no longer the file that failed.
        receiverFailure = receiverFailure?.takeIf { info == null || it.fileId == fileId }
        val tracks = info?.mediaTracks.orEmpty()
        mutableStatus.value = latestLoad.failure ?: receiverFailure ?: CastRemoteStatus(
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
            audioTracks = tracks.castTracks(MediaTrack.TYPE_AUDIO),
            textTracks = tracks.castTracks(MediaTrack.TYPE_TEXT),
            activeTrackIds = media.activeTrackIds?.toSet().orEmpty(),
        )
    }

    /**
     * The receiver reported [state] for [fileId]. A report never settles a load: one for the same file
     * sent again can be about the media before it, so only the load's own result does.
     */
    private fun noteReport(fileId: Long?, state: CastRemoteState) {
        lastFileId = fileId
        // A load's own intent stands until its result: reports on the media before it say nothing of it.
        if (!latestLoad.inFlight && (state == CastRemoteState.Playing || state == CastRemoteState.Paused)) {
            playIntent = state == CastRemoteState.Playing
        }
    }

    override fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float) {
        val remote = client ?: return
        lastFileId = media.fileId
        val request = latestLoad.start()
        pendingSubtitle = media.playlistSubtitle?.let { media.fileId to it }
        playIntent = autoplay
        lastLoad = media to startMillis
        receiverFailure = null
        remote.load(media.loadRequest(startMillis, autoplay, speed, putioReceiver)).setResultCallback { result ->
            // A refused or timed-out load says so for its file, unless another load followed it.
            val failure = media.failedAt(startMillis).takeUnless { result.status.isSuccess }
            if (client === remote && latestLoad.settle(request, failure)) {
                if (failure == null) {
                    showPendingSubtitle()
                } else {
                    pendingSubtitle = null
                    mutableStatus.value = failure
                }
            }
        }
    }

    /**
     * The put.io receiver said how its load went. A failure for the latest load's file, or one it
     * could not tie to a file while that load is in flight, stands until the next load.
     */
    fun receiverReported(state: PutioReceiverState) {
        when (state) {
            is PutioReceiverState.Failed -> state.fileId?.let(::receiverFailed)
            is PutioReceiverState.Loading -> if (state.fileId != receiverFailure?.fileId) clearReceiverFailure()
            is PutioReceiverState.Loaded -> if (state.fileId != receiverFailure?.fileId) clearReceiverFailure()
            PutioReceiverState.Idle -> Unit
        }
    }

    /** The receiver's stream for [fileId] failed: it shows failed where it stood, until the next load. */
    private fun receiverFailed(fileId: Long) {
        val now = SystemClock.elapsedRealtime()
        val shown = mutableStatus.value?.takeIf { it.fileId == fileId }
        val loaded = lastLoad?.takeIf { (media, _) -> media.fileId == fileId }
        val failure = shown
            ?.copy(state = CastRemoteState.Failed, positionMillis = shown.positionAt(now), sampledAtMillis = now)
            ?: loaded?.let { (media, start) -> media.failedAt(start) }
            ?: return
        receiverFailure = failure
        pendingSubtitle = null
        mutableStatus.value = latestLoad.failure ?: failure
    }

    private fun clearReceiverFailure() {
        if (receiverFailure == null) return
        receiverFailure = null
        publish()
    }

    override fun setActiveTracks(trackIds: Set<Long>) {
        client?.setActiveMediaTracks(trackIds.toLongArray())
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

internal sealed interface CastLoadPhase {
    data object Idle : CastLoadPhase

    data class InFlight(val request: Long) : CastLoadPhase

    data class Failed(val status: CastRemoteStatus) : CastLoadPhase
}

internal class CastLoad {
    private var lastRequest = 0L

    var phase: CastLoadPhase = CastLoadPhase.Idle
        private set

    val inFlight: Boolean get() = phase is CastLoadPhase.InFlight

    val failure: CastRemoteStatus? get() = (phase as? CastLoadPhase.Failed)?.status

    fun start(): Long {
        lastRequest++
        phase = CastLoadPhase.InFlight(lastRequest)
        return lastRequest
    }

    fun settle(request: Long, failure: CastRemoteStatus?): Boolean {
        if (phase != CastLoadPhase.InFlight(request)) return false
        phase = failure?.let(CastLoadPhase::Failed) ?: CastLoadPhase.Idle
        return true
    }

    fun reset() {
        phase = CastLoadPhase.Idle
    }
}

/** The presets a Cast receiver plays: it takes 0.5x to 2x. */
internal val CAST_PLAYBACK_SPEEDS: List<Float> = PLAYBACK_SPEEDS.filter {
    it.toDouble() in MediaLoadRequestData.PLAYBACK_RATE_MIN..MediaLoadRequestData.PLAYBACK_RATE_MAX
}

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

/**
 * Turns on the playlist subtitle a load asked for, once the receiver lists the text tracks of that
 * file's stream: an HLS playlist's own tracks exist only after it loads. Returns what still waits.
 */
private fun RemoteMediaClient.showPlaylistSubtitle(
    pending: Pair<Long, PlaybackCastPlaylistSubtitle>,
    putioReceiver: Boolean,
): Pair<Long, PlaybackCastPlaylistSubtitle>? {
    val (fileId, wanted) = pending
    val tracks = mediaInfo?.takeIf { it.putioFileId(putioReceiver) == fileId }
        ?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_TEXT }
    if (tracks.isEmpty()) return pending
    // The audio track playing stays; only the text track changes.
    val kept = mediaStatus?.activeTrackIds?.toSet().orEmpty() - tracks.map { it.id }.toSet()
    playlistSubtitleTrack(tracks.map { CastTextTrack(it.id, it.language) }, wanted)
        ?.let { setActiveMediaTracks((kept + it).toLongArray()) }
    return null
}

private const val PROGRESS_INTERVAL_MILLIS = 1_000L
