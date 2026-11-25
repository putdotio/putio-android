package io.putdotio.android.tv.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.TelemetryFailure
import java.io.Closeable

internal class TvPlaybackService : MediaSessionService() {
    private var hostedSession: MediaSession? = null
    private var stopping = false
    private var taskRemoved = false
    private var observedPlayer: Player? = null
    private val clearTaskRemoved: () -> Unit = { taskRemoved = false }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (taskRemoved && tvPlaybackStopsWithTask(player)) {
                audioHost.stop()
                stopSelf()
            }
        }
    }
    private val sync: (MediaSession?) -> Unit = { hosted ->
        observePlayer(hosted?.player)
        if (hosted != null) {
            stopping = false
            hostedSession = hosted
            if (!isSessionAdded(hosted)) addSession(hosted)
            sessions.filter { it !== hosted }.forEach(::removeSession)
        } else {
            stopping = true
            taskRemoved = false
            hostedSession = null
            sessions.toList().forEach(::removeSession)
            TvBackgroundSessions.detach(sync)
            TvBackgroundSessions.detachForeground(clearTaskRemoved)
            stopSelf()
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(DesignR.drawable.ic_ph_file_audio_fill) },
        )
        TvBackgroundSessions.attachForeground(clearTaskRemoved)
        TvBackgroundSessions.attach(sync)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (TvBackgroundSessions.hosted != null) {
            TvBackgroundSessions.attachForeground(clearTaskRemoved)
            TvBackgroundSessions.attach(sync)
        }
        val result = super.onStartCommand(intent, flags, startId)
        if (TvBackgroundSessions.hosted == null && intent?.action != Intent.ACTION_MEDIA_BUTTON) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        TvBackgroundSessions.hosted

    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        if (tvPlaybackStopsWithTask(TvBackgroundSessions.hosted?.player)) {
            audioHost.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        observePlayer(null)
        TvBackgroundSessions.detach(sync)
        TvBackgroundSessions.detachForeground(clearTaskRemoved)
        sessions.forEach(::removeSession)
        val activeHosted = hostedSession
        hostedSession = null
        if (!stopping && activeHosted != null && TvBackgroundSessions.hosted === activeHosted) {
            audioHost.stop()
        }
        super.onDestroy()
    }

    companion object {
        internal val audioHost: TvAudioPlaybackHost = TvAudioPlaybackHost()

        internal fun resetForTests() {
            audioHost.resetForTests()
            TvBackgroundSessions.resetForTests()
        }
    }

    private fun observePlayer(player: Player?) {
        if (observedPlayer === player) return
        observedPlayer?.removeListener(playerListener)
        observedPlayer = player
        player?.addListener(playerListener)
    }
}

internal object TvBackgroundSessions {
    var hosted: MediaSession? = null
        private set
    private var host: ((MediaSession?) -> Unit)? = null
    private var onForeground: (() -> Unit)? = null
    internal var reportStartFailure: (TelemetryFailure) -> Unit = { PutioDiagnostics.capture(it) }

    fun host(context: Context, session: MediaSession): Closeable {
        hosted = session
        host?.invoke(session)
        try {
            context.startService(Intent(context, TvPlaybackService::class.java))
        } catch (_: IllegalStateException) {
            reportStartFailure(
                TelemetryFailure(
                    type = "tv_playback_service_start_failed",
                    reasons = listOf("background_start_not_allowed"),
                    tags = mapOf("surface" to "tv_audio"),
                ),
            )
        }
        return Closeable {
            if (hosted === session) hosted = null
            host?.invoke(hosted)
            session.release()
        }
    }

    fun refresh(session: MediaSession) {
        if (hosted === session) host?.invoke(session)
    }

    fun attach(sync: (MediaSession?) -> Unit) {
        host = sync
        hosted?.let(sync)
    }

    fun detach(sync: (MediaSession?) -> Unit) {
        if (host === sync) host = null
    }

    fun attachForeground(callback: () -> Unit) {
        onForeground = callback
    }

    fun detachForeground(callback: () -> Unit) {
        if (onForeground === callback) onForeground = null
    }

    fun markAppForeground() {
        onForeground?.invoke()
    }

    fun resetForTests() {
        host = null
        onForeground = null
        hosted?.release()
        hosted = null
        reportStartFailure = { PutioDiagnostics.capture(it) }
    }
}

internal fun tvPlaybackStopsWithTask(player: Player?): Boolean {
    if (player == null || player.mediaItemCount == 0 || !player.playWhenReady) return true
    val state = player.playbackState
    return state != Player.STATE_BUFFERING && state != Player.STATE_READY
}
