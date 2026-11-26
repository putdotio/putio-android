package io.putdotio.android.playback

import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackPreference

public fun AndroidAppConfigState.playbackPreference(): PlaybackPreference =
    confirmedStreamingMethod().playbackPreference()

public fun StreamingMethod.playbackPreference(): PlaybackPreference =
    when (this) {
        StreamingMethod.File -> PlaybackPreference.MP4
        StreamingMethod.Hls -> PlaybackPreference.HLS
    }

public fun AndroidAppConfigState.confirmedAutoplayNextVideo(): Boolean =
    confirmedPreferences?.autoplayNextVideo == true

public fun AndroidAppConfigState.confirmedRotateToLandscape(): Boolean =
    confirmedPreferences?.rotateToLandscape ?: true

public fun AndroidAppConfigState.confirmedStreamingMethod(): StreamingMethod =
    confirmedPreferences?.streamingMethod ?: StreamingMethod.Hls

public fun AccountSettingsState.subtitleStartupPolicy(): SubtitleStartupPolicy? =
    (content as? AccountSettingsContent.Ready)
        ?.preferences
        ?.let { SubtitleStartupPolicy(it.showSubtitles, it.autoSelectSubtitles) }

public fun AccountSettingsState.nextEpisodeEnabled(): Boolean =
    (content as? AccountSettingsContent.Ready)?.preferences?.nextEpisode == true

public fun SubtitleStartupPolicy?.orHiddenWhen(resolvedHidden: Boolean): SubtitleStartupPolicy? =
    this ?: SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = false).takeIf { resolvedHidden }
