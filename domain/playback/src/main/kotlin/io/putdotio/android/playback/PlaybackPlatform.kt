package io.putdotio.android.playback

/** The app a playback report comes from, as put.io's playback failure reports name it. */
public enum class PlaybackPlatform(
    internal val tag: String,
) {
    Mobile("android"),
    Tv("android_tv"),
}
