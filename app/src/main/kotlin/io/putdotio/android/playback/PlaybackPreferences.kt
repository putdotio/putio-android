package io.putdotio.android.playback

import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.files.PlaybackPreference

internal fun AndroidAppConfigState.playbackPreference(): PlaybackPreference =
    when (confirmedPreferences?.videoPlaybackType) {
        VideoPlaybackType.Mp4 -> PlaybackPreference.MP4
        VideoPlaybackType.Hls,
        null,
        -> PlaybackPreference.HLS
    }

internal fun AndroidAppConfigState.confirmedAutoplayNextVideo(): Boolean {
    val ready = content as? AndroidAppConfigContent.Ready ?: return false
    val preferences =
        when (val currentMutation = mutation) {
            AndroidAppConfigMutation.Idle -> ready.preferences
            is AndroidAppConfigMutation.Saving -> currentMutation.previousPreferences
            is AndroidAppConfigMutation.Failed -> currentMutation.previousPreferences
        }
    return preferences.autoplayNextVideo
}
