package io.putdotio.android.playback

public class OpenSourceNotice internal constructor(
    public val name: String,
    public val version: String,
    public val license: String,
    public val copyright: String,
    public val sourceUrl: String,
    public val licenseUrl: String,
)

public val openSourceNotices: List<OpenSourceNotice> = listOf(
    OpenSourceNotice(
        name = "nextlib",
        version = "1.11.1-0.17.0",
        license = "GPL-3.0",
        copyright = "Copyright © Anil Kumar Beesetti",
        sourceUrl = "https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.17.0",
        licenseUrl = "https://www.gnu.org/licenses/gpl-3.0.html",
    ),
    OpenSourceNotice(
        name = "FFmpeg",
        version = "9.0.1",
        license = "LGPL-3.0-or-later",
        copyright = "Copyright © the FFmpeg developers",
        sourceUrl = "https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz",
        licenseUrl = "https://www.gnu.org/licenses/lgpl-3.0.html",
    ),
    OpenSourceNotice(
        name = "dav1d",
        version = "1.5.4",
        license = "BSD-2-Clause",
        copyright = "Copyright © 2018-2025, VideoLAN and dav1d authors",
        sourceUrl = "https://code.videolan.org/videolan/dav1d/-/tree/1.5.4",
        licenseUrl = "https://code.videolan.org/videolan/dav1d/-/blob/1.5.4/COPYING",
    ),
    OpenSourceNotice(
        name = "Mbed TLS",
        version = "3.6.7",
        license = "Apache-2.0",
        copyright = "Copyright © The Mbed TLS Contributors",
        sourceUrl = "https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.7",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    ),
)
