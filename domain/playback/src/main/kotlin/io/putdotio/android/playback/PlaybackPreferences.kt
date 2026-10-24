package io.putdotio.android.playback

import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.files.PlaybackPreference

public fun AndroidAppConfigState.playbackPreference(): PlaybackPreference =
    when (confirmedPreferences?.videoPlaybackType) {
        VideoPlaybackType.Mp4 -> PlaybackPreference.MP4
        VideoPlaybackType.Hls,
        null,
        -> PlaybackPreference.HLS
    }

public fun AndroidAppConfigState.confirmedAutoplayNextVideo(): Boolean =
    confirmedPreferences?.autoplayNextVideo == true

/** The account's `hide_subtitles` and `dont_autoselect_subtitles`, once settings have loaded. */
public fun AccountSettingsState.subtitleStartupPolicy(): SubtitleStartupPolicy? =
    (content as? AccountSettingsContent.Ready)
        ?.preferences
        ?.let { SubtitleStartupPolicy(it.showSubtitles, it.autoSelectSubtitles) }

/** The account's `next_episode`, once settings have loaded: the video player offers the next video. */
public fun AccountSettingsState.nextEpisodeEnabled(): Boolean =
    (content as? AccountSettingsContent.Ready)?.preferences?.nextEpisode == true

/**
 * The account's policy, else hidden when playback resolved under `hide_subtitles` before the
 * account settings loaded, so the picker never shows for such an account.
 */
public fun SubtitleStartupPolicy?.orHiddenWhen(resolvedHidden: Boolean): SubtitleStartupPolicy? =
    this ?: SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = false).takeIf { resolvedHidden }
