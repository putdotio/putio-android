package io.putdotio.android.playback

import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.files.PlaybackPreference

internal fun AndroidAppConfigState.playbackPreference(): PlaybackPreference =
    when ((content as? AndroidAppConfigContent.Ready)?.preferences?.videoPlaybackType) {
        VideoPlaybackType.Mp4 -> PlaybackPreference.MP4
        VideoPlaybackType.Hls,
        null,
        -> PlaybackPreference.HLS
    }
