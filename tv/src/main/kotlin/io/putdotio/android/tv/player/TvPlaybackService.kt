package io.putdotio.android.tv.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.putdotio.android.design.R as DesignR
import java.io.Closeable

internal class TvPlaybackService : MediaSessionService() {
    private val sync: (MediaSession?) -> Unit = { hosted ->
        sessions.filter { it !== hosted }.forEach(::removeSession)
        when {
            hosted == null -> stopSelf()
            !isSessionAdded(hosted) -> addSession(hosted)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        TvBackgroundSessions.hosted

    override fun onDestroy() {
        TvBackgroundSessions.detach(sync)
        sessions.forEach(::removeSession)
        super.onDestroy()
    }
}

internal object TvBackgroundSessions {
    var hosted: MediaSession? = null
        private set
    private var host: ((MediaSession?) -> Unit)? = null

    fun host(context: Context, session: MediaSession): Closeable {
        hosted = session
        host?.invoke(session)
        try {
            context.startService(Intent(context, TvPlaybackService::class.java))
        } catch (_: IllegalStateException) {
            Unit
        }
        return Closeable {
            if (hosted === session) hosted = null
            host?.invoke(hosted)
            session.release()
        }
    }

    fun attach(sync: (MediaSession?) -> Unit) {
        host = sync
        sync(hosted)
    }

    fun detach(sync: (MediaSession?) -> Unit) {
        if (host === sync) host = null
    }
}
