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

internal object MobileCast {
    @Volatile
    @VisibleForTesting
    internal var controller: MobileCastController? = null

    private val castSessionPlayers: MutableSet<Player> = Collections.newSetFromMap(IdentityHashMap())

    @VisibleForTesting
    internal var castScope: CoroutineScope = MainScope()
        private set

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
                    DefaultMobilePlayerFactory.observePositions(appContext, cast.remotePlayer)
                    castScope.launch { cast.finished.collect { onCastFinished(it) } }
                    holdSessionWhileCasting(appContext, cast)
                    controller = cast
                }
            }
        }
    }

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

    fun sessionLeft(context: Context, session: Any?) {
        val appContext = context.applicationContext
        val leave = {
            val cast = controller(appContext)
            if (cast != null) {
                cast.end()
                cast.autoplay.forget(session)
                if (cast.gateway !is GmsCastGateway) {
                    CastReceiverStore.clearPutio(appContext)
                }
            } else {
                CastReceiverStore.clearPutio(appContext)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            leave()
        } else {
            val livePutio = (controller?.gateway as? GmsCastGateway)?.hasLivePutioSession == true
            CastReceiverStore.markSignOutPendingIfPutio(appContext, livePutioSession = livePutio)
            Handler(Looper.getMainLooper()).post(leave)
        }
    }

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

class MobileCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(receiverApplicationId(CastReceiverStore.bootWithPutio(context)))
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
    private var endInFlightStart = false

    val isPending: Boolean get() = pending

    fun armPending() {
        pending = true
    }

    fun requested(connecting: Boolean = false) {
        pending = true
        if (connecting) endInFlightStart = true
        end()
    }

    fun resumed(attach: () -> Unit) = if (pending) end() else attach()

    fun started(attach: () -> Unit) {
        if (endInFlightStart) {
            endInFlightStart = false
            end()
        } else {
            pending = false
            attach()
        }
    }

    fun sessionStarted() {
        pending = false
        endInFlightStart = false
    }

    fun sessionEnded() {
        pending = false
        endInFlightStart = false
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

internal interface CastSessionEvents {
    fun onStarting()

    fun onStarted(session: CastSession)

    fun onStartFailed()

    fun onEnding()

    fun onEnded()

    fun onResuming()

    fun onResumed(session: CastSession)

    fun onResumeFailed()

    fun onSuspended()
}

private class PendingSignOutGrace(
    private val schedule: (Runnable) -> Closeable,
    private val onExpired: () -> Unit,
) {
    private var pending: Closeable? = null

    fun arm() {
        cancel()
        pending = schedule(
            Runnable {
                pending = null
                onExpired()
            },
        )
    }

    fun cancel() {
        pending?.closeQuietly()
        pending = null
    }
}

internal class GmsCastGateway @VisibleForTesting internal constructor(
    private val context: Context,
    private val routes: GmsCastRoutes,
    private val remote: GmsCastRemote,
    private val localNetworkDenied: () -> Boolean = { context.localNetworkNotGranted() },
    private val schedulePendingSignOutTimeout: (Runnable) -> Closeable = ::defaultPendingSignOutTimeout,
    private val loadCast: (Context) -> Task<CastContext>,
) : CastGateway, CastRoutes by routes, CastRemote by remote {
    constructor(context: Context) : this(context, GmsCastRoutes(context), GmsCastRemote(), loadCast = ::loadCastContext)

    private val mutableAvailability = MutableStateFlow<CastAvailability>(CastAvailability.Unsupported)
    override val availability: StateFlow<CastAvailability> = mutableAvailability
    private val mutableMovesHere = MutableStateFlow(false)
    override val movesHere: StateFlow<Boolean> = mutableMovesHere
    private var castContext: CastContextHandle? = null
    private val signOut = CastSignOutEnd { castContext?.endCurrentSession(true) }
    private val startedOwners: MutableSet<LifecycleOwner> = Collections.newSetFromMap(IdentityHashMap())
    val receivers = CastReceiverSwitch(context, routes::reselect)
    private val messages = PutioReceiverMessages(remote::receiverReported)
    private var sessionStarting = false
    @Volatile
    internal var hasLivePutioSession: Boolean = false
        private set
    private val pendingSignOutGrace = PendingSignOutGrace(schedulePendingSignOutTimeout) {
        if (castContext?.isSessionLive() != true) {
            receivers.sessionGone()
        }
    }

    init {
        remote.onAttachedChanged = { updateAvailability() }
        routes.onDevicesChanged = { updateAvailability() }
        if (CastReceiverStore.pendingSignOutEnd(context)) {
            remote.discardHandback = true
            signOut.armPending()
            receivers.drainPendingSignOut()
        }
    }

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
        if (signOut.isPending && cast.isSessionLive() && cast.isPutioSession()) {
            receivers.markLivePutioSignOutPending()
        }
        receivers.bind(cast)
        cast.addCastStateListener {
            receivers.retryIfIdle()
            updateAvailability()
        }
        cast.addSessionManagerListener(sessions)
        cast.addSessionTransferCallback(transfers)
        val current = cast.currentCastSession
        if (signOut.isPending && (cast.castState == CastState.CONNECTING || current?.isConnecting == true)) {
            signOut.requested(connecting = true)
        } else if (current != null) {
            signOut.resumed { if (current.isConnected) attach(current) }
        } else if (cast.castState == CastState.CONNECTED) {
            signOut.resumed {}
        }
        if (signOut.isPending && !cast.isSessionLive() && CastReceiverStore.pendingSignOutEnd(context)) {
            pendingSignOutGrace.arm()
        }
        if (startedOwners.isNotEmpty()) routes.watch()
        updateAvailability()
    }

    private fun updateAvailability() {
        val cast = castContext ?: return
        hasLivePutioSession = cast.isSessionLive() && cast.isPutioSession()
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

    @VisibleForTesting
    internal val sessionEvents: CastSessionEvents = object : CastSessionEvents {
        override fun onStarting() {
            sessionStarting = true
            pendingSignOutGrace.cancel()
            mutableMovesHere.value = false
            updateAvailability()
        }

        override fun onStarted(session: CastSession) {
            sessionStarting = false
            pendingSignOutGrace.cancel()
            signOut.started {
                receivers.sessionStarted()
                remote.discardHandback = false
                attach(session)
            }
        }

        override fun onStartFailed() {
            sessionStarting = false
            pendingSignOutGrace.cancel()
            signOut.sessionEnded()
            updateAvailability()
            receivers.sessionGone()
        }

        override fun onEnding() = remote.publish()

        override fun onEnded() {
            sessionStarting = false
            pendingSignOutGrace.cancel()
            signOut.sessionEnded()
            detach()
            receivers.sessionGone()
        }

        override fun onResuming() {
            pendingSignOutGrace.cancel()
            mutableMovesHere.value = false
            signOut.resumed {}
            updateAvailability()
        }

        override fun onResumed(session: CastSession) {
            pendingSignOutGrace.cancel()
            signOut.resumed { attach(session) }
        }

        override fun onResumeFailed() {
            pendingSignOutGrace.cancel()
            signOut.sessionEnded()
            detach()
            receivers.sessionGone()
        }

        override fun onSuspended() = remote.publish()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = sessionEvents.onStarting()

        override fun onSessionStarted(session: CastSession, sessionId: String) = sessionEvents.onStarted(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) = sessionEvents.onStartFailed()

        override fun onSessionEnding(session: CastSession) = sessionEvents.onEnding()

        override fun onSessionEnded(session: CastSession, error: Int) = sessionEvents.onEnded()

        override fun onSessionResuming(session: CastSession, sessionId: String) = sessionEvents.onResuming()

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = sessionEvents.onResumed(session)

        override fun onSessionResumeFailed(session: CastSession, error: Int) = sessionEvents.onResumeFailed()

        override fun onSessionSuspended(session: CastSession, reason: Int) = sessionEvents.onSuspended()
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

    override fun endForSignOut() {
        val connecting = sessionStarting || castContext?.castState == CastState.CONNECTING
        pendingSignOutGrace.cancel()
        remote.discardHandback = true
        receivers.signOut()
        signOut.requested(connecting = connecting)
    }

    override fun stopCasting() {
        castContext?.endCurrentSession(true)
    }

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

private fun defaultPendingSignOutTimeout(task: Runnable): Closeable {
    val handler = Handler(Looper.getMainLooper())
    handler.postDelayed(task, PENDING_SIGN_OUT_TIMEOUT_MILLIS)
    return Closeable { handler.removeCallbacks(task) }
}

private const val PENDING_SIGN_OUT_TIMEOUT_MILLIS = 3_000L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun loadCastContext(context: Context): Task<CastContext> =
    CastContext.getSharedInstance(context, BackgroundExecutor.get())

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

    private var pendingSubtitle: Pair<Long, PlaybackCastPlaylistSubtitle>? = null

    private var putioReceiver = false

    private var lastLoad: Pair<PlaybackCastMedia, Long>? = null

    private var receiverFailure: CastRemoteStatus? = null

    private var unplacedFailure: Long? = null

    private var playIntent: Boolean? = null

    private var statusKnown = false

    var discardHandback = false

    var onAttachedChanged: () -> Unit = {}

    val attached: Boolean get() = client != null && statusKnown

    private val markStatusKnown = {
        if (client != null && !statusKnown) {
            statusKnown = true
            onAttachedChanged()
        }
    }

    private val clearIfEmpty = {
        if (client?.mediaStatus == null) mutableStatus.value = null
    }

    private val showPendingSubtitle = {
        val remote = client
        if (!latestLoad.inFlight && remote != null) {
            pendingSubtitle = pendingSubtitle?.let { remote.showPlaylistSubtitle(it, keepAudio = putioReceiver) }
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

    fun detach() {
        publish()
        release()
        lastFileId = null
        latestLoad.reset()
        putioReceiver = false
        lastLoad = null
        receiverFailure = null
        unplacedFailure = null
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
        val info = remote.mediaInfo
        val fileId = info?.putioFileId()
            ?: lastFileId.takeIf { info == null && media.playerState == MediaStatus.PLAYER_STATE_IDLE }
        val previous = mutableStatus.value?.takeIf { info == null && it.fileId == fileId }
        val state = media.castRemoteState()
        noteReport(fileId, state)
        receiverFailure = receiverFailure?.takeIf {
            info == null || it.fileId == fileId || it.fileId == lastLoad?.first?.fileId
        }
        val tracks = info?.mediaTracks.orEmpty().takeIf { putioReceiver }.orEmpty()
        val status = CastRemoteStatus(
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
        placeUnplacedFailure(status)
        mutableStatus.value = latestLoad.failure ?: receiverFailure ?: status
    }

    private fun placeUnplacedFailure(status: CastRemoteStatus) {
        if (status.fileId == null || unplacedFailure != status.fileId) return
        unplacedFailure = null
        receiverFailure = status.copy(state = CastRemoteState.Failed)
    }

    private fun noteReport(fileId: Long?, state: CastRemoteState) {
        lastFileId = fileId
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
        unplacedFailure = null
        remote.load(media.loadRequest(startMillis, autoplay, speed, putioReceiver)).setResultCallback { result ->
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

    fun receiverReported(state: PutioReceiverState) {
        when (state) {
            is PutioReceiverState.Failed ->
                (state.fileId ?: lastLoad?.first?.fileId?.takeIf { latestLoad.inFlight })?.let(::receiverFailed)
            is PutioReceiverState.Loading, is PutioReceiverState.Loaded -> clearReceiverFailure()
            PutioReceiverState.Idle -> Unit
        }
    }

    private fun receiverFailed(fileId: Long) {
        val now = SystemClock.elapsedRealtime()
        val shown = mutableStatus.value?.takeIf { it.fileId == fileId }
        val loaded = lastLoad?.takeIf { (media, _) -> media.fileId == fileId }
        val failure = shown
            ?.copy(state = CastRemoteState.Failed, positionMillis = shown.positionAt(now), sampledAtMillis = now)
            ?: loaded?.let { (media, start) -> media.failedAt(start) }
        if (failure == null) {
            unplacedFailure = fileId
            return
        }
        receiverFailure = failure
        pendingSubtitle = null
        mutableStatus.value = latestLoad.failure ?: failure
    }

    private fun clearReceiverFailure() {
        unplacedFailure = null
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

private fun RemoteMediaClient.showPlaylistSubtitle(
    pending: Pair<Long, PlaybackCastPlaylistSubtitle>,
    keepAudio: Boolean,
): Pair<Long, PlaybackCastPlaylistSubtitle>? {
    val (fileId, wanted) = pending
    val tracks = mediaInfo?.takeIf { it.putioFileId() == fileId }
        ?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_TEXT }
    if (tracks.isEmpty()) return pending
    val kept = mediaStatus?.activeTrackIds?.toSet().orEmpty().takeIf { keepAudio }.orEmpty() -
        tracks.map { it.id }.toSet()
    playlistSubtitleTrack(tracks.map { CastTextTrack(it.id, it.language) }, wanted)
        ?.let { setActiveMediaTracks((kept + it).toLongArray()) }
    return null
}

private const val PROGRESS_INTERVAL_MILLIS = 1_000L
