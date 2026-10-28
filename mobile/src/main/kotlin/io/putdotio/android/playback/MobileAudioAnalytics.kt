package io.putdotio.android.playback

/**
 * The last opened audio playback's analytics, for the session service: it keeps playing, and sees
 * the end, after the player screen has gone. The next audio playback replaces it.
 */
internal object MobileAudioAnalytics {
    @Volatile
    var reporter: PlaybackAnalyticsReporter = PlaybackAnalyticsReporter.None
}
