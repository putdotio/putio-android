package io.putdotio.android

import android.content.Context
import androidx.media3.common.Player as Media3Player
import io.putdotio.android.playback.PlaybackMediaType
import java.io.Closeable

/**
 * Robolectric cannot bind the session service (its service connection reports a null
 * component), so shell tests that do not exercise audio report no session instead.
 */
internal object NoAudioSessionFactory : MobilePlayerFactory {
    override fun create(context: Context, mediaType: PlaybackMediaType): Media3Player =
        DefaultMobilePlayerFactory.create(context, mediaType)

    override fun connectAudio(
        context: Context,
        onResult: (Result<Media3Player>) -> Unit,
    ): Closeable {
        onResult(Result.failure(IllegalStateException("no audio session in tests")))
        return Closeable {}
    }
}
