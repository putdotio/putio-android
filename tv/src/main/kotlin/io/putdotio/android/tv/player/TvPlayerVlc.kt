package io.putdotio.android.tv.player

import android.content.Context
import androidx.annotation.StringRes
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.files.openInVlc

@StringRes
internal fun FilesStreamUrlResult.openPlaybackInVlc(context: Context, target: PlaybackTarget): Int? =
    openInVlc(context, target.name, audio = target.mediaType == PlaybackMediaType.AUDIO) { failure ->
        when (failure) {
            is PutioFailure.AuthenticationRequired -> null
            is PutioFailure.NetworkUnavailable -> R.string.tv_error_network
            else -> R.string.tv_error_unavailable
        }
    }
