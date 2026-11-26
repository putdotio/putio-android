package io.putdotio.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.playback.AudioSessionAnalytics
import io.putdotio.android.playback.CastStopCallback
import io.putdotio.android.playback.CastStopControl
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.playback.MobileSleepTimer
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackPlatform
import io.putdotio.android.playback.PlaybackPositionObserver
import io.putdotio.android.playback.playerFailureTelemetry
import io.putdotio.android.telemetry.PutioDiagnostics
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MobilePlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var local: Media3Player? = null
    private var taskRemoved = false
    private var audioStarted = false
    private var positionObserver: PlaybackPositionObserver? = null
    private var analyticsObserver: Closeable? = null
    private var localListener: Media3Player.Listener? = null
    private var sessionListener: Media3Player.Listener? = null
    private val scope = MainScope()
    private val castStop by lazy { CastStopControl(this) { MobileCast.stopCasting(this) } }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        val player = createLocalPlayer(this)
        local = player
        (player as? ExoPlayer)?.setWakeMode(C.WAKE_MODE_NETWORK)
        val downloads = MobileDownloadCache.get(this)
        val runtime = MobileOAuthRuntime.get(this)
        runtime.ensureSessionRestored(downloads::markSessionSettled)
        positionObserver = runtime.playbackReporting.observe(player)
        analyticsObserver = analytics.observe(player)
        localListener = localPlayerListener().also(player::addListener)
        val sessionPlayer = createSessionPlayer(this, player, scope) { positionObserver?.flush() }
        sessionListener =
            object : Media3Player.Listener {
                override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                    if (taskRemoved && player.stopsWithTask()) stopSelf()
                }

                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    session?.setMediaButtonPreferences(castStop.buttons(deviceInfo))
                }
            }.also(sessionPlayer::addListener)
        session =
            MediaSession.Builder(this, sessionPlayer)
                .setCallback(CastStopCallback(castStop))
                .setMediaButtonPreferences(castStop.buttons(sessionPlayer.deviceInfo))
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java)
                            .setAction(ACTION_OPEN_NOW_PLAYING)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(DesignR.drawable.ic_ph_file_audio_fill) },
        )
        scope.launch { MobileSleepTimer.Shared.pauseWhenDue { session?.player?.pause() } }
    }

    private fun localPlayerListener(): Media3Player.Listener =
        object : Media3Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) audioStarted = true
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                audioStarted = false
            }

            override fun onPlayerError(error: PlaybackException) {
                val player = local ?: return
                val audio = PlaybackMediaType.AUDIO
                val telemetry = player.playerFailureTelemetry(error, audio, PlaybackPlatform.Mobile, audioStarted)
                PutioDiagnostics.capture(telemetry)
            }
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (controllerInfo.packageName == packageName) taskRemoved = false
        return session
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            positionObserver?.flush()
            local?.let {
                it.stop()
                it.clearMediaItems()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        val player = session?.player
        if (player == null || player.stopsWithTask()) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        MobileSleepTimer.Shared.cancel()
        positionObserver?.close()
        positionObserver = null
        analyticsObserver?.close()
        analyticsObserver = null
        localListener?.let { local?.removeListener(it) }
        localListener = null
        session?.let {
            val sessionPlayer = it.player
            sessionListener?.let(sessionPlayer::removeListener)
            it.release()
            releaseSessionPlayer(this, sessionPlayer)
        }
        sessionListener = null
        session = null
        local = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "io.putdotio.android.action.OPEN_NOW_PLAYING"
        private const val ACTION_STOP = "io.putdotio.android.action.STOP_PLAYBACK"

        val analytics = AudioSessionAnalytics()

        @VisibleForTesting
        internal var createLocalPlayer: (Context) -> Media3Player = {
            DefaultMobilePlayerFactory.create(it, PlaybackMediaType.AUDIO)
        }

        @VisibleForTesting
        internal var createSessionPlayer: (Context, Media3Player, CoroutineScope, () -> Unit) -> Media3Player =
            MobileCast::sessionPlayer

        @VisibleForTesting
        internal var releaseSessionPlayer: (Context, Media3Player) -> Unit = MobileCast::releaseSessionPlayer

        fun sessionToken(context: Context): SessionToken =
            SessionToken(context, ComponentName(context, MobilePlaybackService::class.java))

        fun stop(context: Context) {
            MobileSleepTimer.Shared.cancel()
            val intent = Intent(context, MobilePlaybackService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                stopThroughController(context.applicationContext)
                context.stopService(Intent(context, MobilePlaybackService::class.java))
            }
        }

        private fun stopThroughController(context: Context) {
            val future = MediaController.Builder(context, sessionToken(context)).buildAsync()
            future.addListener(
                {
                    runCatching { future.get() }.onSuccess {
                        if (it.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) {
                            it.stop()
                            it.clearMediaItems()
                        }
                    }
                    MediaController.releaseFuture(future)
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }
}

private fun Media3Player.stopsWithTask(): Boolean =
    playbackStopsWithTask(playWhenReady, playbackState, mediaItemCount)

internal fun playbackStopsWithTask(
    playWhenReady: Boolean,
    playbackState: Int,
    mediaItemCount: Int,
): Boolean =
    !playWhenReady ||
        mediaItemCount == 0 ||
        playbackState == Media3Player.STATE_ENDED ||
        playbackState == Media3Player.STATE_IDLE
