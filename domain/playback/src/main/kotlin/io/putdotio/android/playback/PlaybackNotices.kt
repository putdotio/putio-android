package io.putdotio.android.playback

import io.putdotio.android.OpenSourceNotice

public val playbackNotices: List<OpenSourceNotice> = listOf(
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
    OpenSourceNotice(
        name = "ass-media",
        version = "0.5.1",
        license = "MIT",
        copyright = "Copyright © 2025 peerless2012",
        sourceUrl = "https://github.com/peerless2012/libass-android/tree/v0.5.1",
        licenseUrl = "https://github.com/peerless2012/libass-android/blob/v0.5.1/LICENSE",
    ),
    OpenSourceNotice(
        name = "libass",
        version = "0.17.4",
        license = "ISC",
        copyright = "Copyright © 2006-2016 libass contributors",
        sourceUrl = "https://github.com/libass/libass/tree/0.17.4",
        licenseUrl = "https://github.com/libass/libass/blob/0.17.4/COPYING",
    ),
    OpenSourceNotice(
        name = "FreeType",
        version = "2.13.3",
        license = "FTL",
        copyright = "Portions of this software are copyright © The FreeType Project (www.freetype.org). " +
            "All rights reserved.",
        sourceUrl = "https://gitlab.freedesktop.org/freetype/freetype/-/tree/VER-2-13-3",
        licenseUrl = "https://gitlab.freedesktop.org/freetype/freetype/-/blob/VER-2-13-3/docs/FTL.TXT",
    ),
    OpenSourceNotice(
        name = "FriBidi",
        version = "1.0.16",
        license = "LGPL-2.1-or-later",
        copyright = "Copyright © the FriBidi authors",
        sourceUrl = "https://github.com/fribidi/fribidi/tree/v1.0.16",
        licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html",
    ),
    OpenSourceNotice(
        name = "HarfBuzz",
        version = "11.3.3",
        license = "MIT",
        copyright = "Copyright © the HarfBuzz authors",
        sourceUrl = "https://github.com/harfbuzz/harfbuzz/tree/11.3.3",
        licenseUrl = "https://github.com/harfbuzz/harfbuzz/blob/11.3.3/COPYING",
    ),
    OpenSourceNotice(
        name = "fontconfig",
        version = "daa175d2",
        license = "HPND-sell-variant",
        copyright = "Copyright © 2000-2007 Keith Packard and the fontconfig authors",
        sourceUrl = "$FONTCONFIG/tree/$FONTCONFIG_COMMIT",
        licenseUrl = "$FONTCONFIG/blob/$FONTCONFIG_COMMIT/COPYING",
    ),
    OpenSourceNotice(
        name = "libunibreak",
        version = "6.1",
        license = "Zlib",
        copyright = "Copyright © Wu Yongwei, Tom Hacohen, Petr Filipsky, Andreas Röver",
        sourceUrl = "https://github.com/adah1972/libunibreak/tree/libunibreak_6_1",
        licenseUrl = "https://github.com/adah1972/libunibreak/blob/libunibreak_6_1/LICENCE",
    ),
    OpenSourceNotice(
        name = "Expat",
        version = "2.7.1",
        license = "MIT",
        copyright = "Copyright © 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper; " +
            "© 2001-2025 Expat maintainers",
        sourceUrl = "https://github.com/libexpat/libexpat/tree/R_2_7_1",
        licenseUrl = "https://github.com/libexpat/libexpat/blob/R_2_7_1/expat/COPYING",
    ),
)

private const val FONTCONFIG = "https://gitlab.freedesktop.org/fontconfig/fontconfig/-"
private const val FONTCONFIG_COMMIT = "daa175d234b8a362eedd4c18c33537cc2d19cd98"
