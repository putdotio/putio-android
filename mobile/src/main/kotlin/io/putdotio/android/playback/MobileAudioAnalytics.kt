package io.putdotio.android.playback

/**
 * The open audio playback's analytics, for the session service: it keeps playing, and sees the
 * end, after the player screen stops listening.
 */
internal object MobileAudioAnalytics {
    @Volatile
    var reporter: PlaybackAnalyticsReporter = PlaybackAnalyticsReporter.None
}
