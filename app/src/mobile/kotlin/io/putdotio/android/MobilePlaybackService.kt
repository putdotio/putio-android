package io.putdotio.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.auth.MobileOAuthRuntime

/**
 * Owns the audio player so playback outlives the player screen: the session
 * drives the foreground notification, lock-screen and headset controls, and
 * audio focus while the app is in the background.
 */
class MobilePlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var taskRemoved = false
    private var positionObserver: MobilePlayerPositionObserver? = null

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        val player = DefaultMobilePlayerFactory.create(this, PlaybackMediaType.AUDIO)
        // Streaming with the screen off needs the CPU and radio awake; a foreground service alone does not.
        (player as? ExoPlayer)?.setWakeMode(C.WAKE_MODE_NETWORK)
        positionObserver = MobileOAuthRuntime.get(this).playbackReporting.observe(player)
        player.addListener(
            object : Media3Player.Listener {
                override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                    if (taskRemoved && player.stopsWithTask()) stopSelf()
                }
            },
        )
        session =
            MediaSession.Builder(this, player)
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
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_ph_file_audio_fill) },
        )
    }

    // The app reconnecting means a task exists again; pausing inside it must not stop us.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (controllerInfo.packageName == packageName) taskRemoved = false
        return session
    }

    // Bound controllers keep the service alive past stopService, so a stop must end
    // playback itself instead of waiting for onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            positionObserver?.flush()
            session?.player?.let {
                it.stop()
                it.clearMediaItems()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // Active playback outlives the task; once it pauses, ends, or fails afterwards the listener stops us.
    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        val player = session?.player
        if (player == null || player.stopsWithTask()) stopSelf()
    }

    override fun onDestroy() {
        positionObserver?.close()
        positionObserver = null
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        /** Launcher intent action from the media notification: land on the live player. */
        const val ACTION_OPEN_NOW_PLAYING = "io.putdotio.android.action.OPEN_NOW_PLAYING"
        private const val ACTION_STOP = "io.putdotio.android.action.STOP_PLAYBACK"

        fun sessionToken(context: Context): SessionToken =
            SessionToken(context, ComponentName(context, MobilePlaybackService::class.java))

        fun stop(context: Context) {
            val intent = Intent(context, MobilePlaybackService::class.java).setAction(ACTION_STOP)
            // In the background a start command may be refused; a bind-only controller can
            // still stop the player, and stopService then lets the unbound service die.
            runCatching { context.startService(intent) }.onFailure {
                stopThroughController(context.applicationContext)
                context.stopService(Intent(context, MobilePlaybackService::class.java))
            }
        }

        private fun stopThroughController(context: Context) {
            val future = MediaController.Builder(context, sessionToken(context)).buildAsync()
            future.addListener(
                {
                    runCatching { future.get() }.onSuccess {
                        it.stop()
                        it.clearMediaItems()
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

// Swiping the task away ends paused or finished audio; active playback keeps going.
internal fun playbackStopsWithTask(
    playWhenReady: Boolean,
    playbackState: Int,
    mediaItemCount: Int,
): Boolean =
    !playWhenReady ||
        mediaItemCount == 0 ||
        playbackState == Media3Player.STATE_ENDED ||
        playbackState == Media3Player.STATE_IDLE
