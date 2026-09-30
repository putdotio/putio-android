package io.putdotio.android.tv.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.audioAttributes
import io.putdotio.android.playback.playbackRenderersFactory
import java.io.Closeable
import java.util.UUID

/** Creates the player one TV playback screen owns and releases, and the session that publishes it. */
internal fun interface TvPlayerFactory {
    fun create(context: Context, mediaType: PlaybackMediaType): Player

    /**
     * Publishes [player] to the system for as long as the screen shows it: remote media keys
     * the screen does not take, the system's media controls and Now Playing. Closing it
     * unpublishes the player, which the screen releases afterwards.
     */
    fun publish(context: Context, player: Player): Closeable = Closeable {}
}

/**
 * A streaming ExoPlayer. The resolved source already carries its media credential, and
 * the default media source factory picks HLS from the item's MIME type.
 */
internal object DefaultTvPlayerFactory : TvPlayerFactory {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        ExoPlayer.Builder(context, playbackRenderersFactory(context))
            .setLoadControl(tvLoadControl())
            .setAudioAttributes(mediaType.audioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()

    override fun publish(context: Context, player: Player): Closeable {
        val launch = (
            context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                ?: Intent(context, context.javaClass)
            ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val session = tvMediaSession(context, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        return Closeable { session.release() }
    }
}

/**
 * A session for the one file the screen plays. Controllers get transport, seek and speed only:
 * a controller that replaced or added media items would play another file while the screen
 * still reports positions for the first.
 */
internal fun tvMediaSession(context: Context, player: Player): MediaSession.Builder =
    MediaSession.Builder(context, player)
        // A recreated screen can publish its player before the old session is gone.
        .setId("tv-player-${UUID.randomUUID()}")
        .setCallback(
            object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(TV_SESSION_PLAYER_COMMANDS)
                        .build()
            },
        )

internal val TV_SESSION_PLAYER_COMMANDS: Player.Commands = Player.Commands.Builder()
    .addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SET_SPEED_AND_PITCH,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        Player.COMMAND_GET_TRACKS,
    )
    .build()

/**
 * The tv-native player's default `medium` buffer (putio-web `apps/tv-native`
 * `lib/queries/remote-config.ts` @ `22264d5`): at least 8 s and at most 30 s ahead, starting
 * after 1.5 s and after 3 s once it has stalled. ExoPlayer's defaults buffer 50 s, more than a
 * low-memory TV stick should hold. The RN app's other sizes had no server key and stay out.
 */
@UnstableApi
internal fun tvLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            TV_MIN_BUFFER_MILLIS,
            TV_MAX_BUFFER_MILLIS,
            TV_BUFFER_FOR_PLAYBACK_MILLIS,
            TV_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS,
        )
        .build()

internal const val TV_MIN_BUFFER_MILLIS = 8_000
internal const val TV_MAX_BUFFER_MILLIS = 30_000
internal const val TV_BUFFER_FOR_PLAYBACK_MILLIS = 1_500
internal const val TV_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS = 3_000
