package io.putdotio.android.playback

import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsState
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

internal fun AndroidAppConfigState.confirmedAutoplayNextVideo(): Boolean =
    confirmedPreferences?.autoplayNextVideo == true

/** The account's `hide_subtitles` and `dont_autoselect_subtitles`, once settings have loaded. */
internal fun AccountSettingsState.subtitleStartupPolicy(): SubtitleStartupPolicy? =
    (content as? AccountSettingsContent.Ready)
        ?.preferences
        ?.let { SubtitleStartupPolicy(it.showSubtitles, it.autoSelectSubtitles) }
