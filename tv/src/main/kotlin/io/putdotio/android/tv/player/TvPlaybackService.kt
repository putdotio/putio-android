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
    private val sync: (MediaSession?) -> Unit = { hosted ->
        if (hosted != null) {
            stopping = false
            hostedSession = hosted
            if (!isSessionAdded(hosted)) addSession(hosted)
            sessions.filter { it !== hosted }.forEach(::removeSession)
        } else {
            stopping = true
            hostedSession = null
            sessions.toList().forEach(::removeSession)
            TvBackgroundSessions.detach(sync)
            stopSelf()
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(DesignR.drawable.ic_ph_file_audio_fill) },
        )
        TvBackgroundSessions.attach(sync)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        if (tvPlaybackStopsWithTask(TvBackgroundSessions.hosted?.player)) {
            audioHost.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        TvBackgroundSessions.detach(sync)
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
}

internal object TvBackgroundSessions {
    var hosted: MediaSession? = null
        private set
    private var host: ((MediaSession?) -> Unit)? = null
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

    fun attach(sync: (MediaSession?) -> Unit) {
        host = sync
        hosted?.let(sync)
    }

    fun detach(sync: (MediaSession?) -> Unit) {
        if (host === sync) host = null
    }

    fun resetForTests() {
        host = null
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
