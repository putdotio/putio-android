package io.putdotio.android.playback

import io.putdotio.android.OpenSourceNotice

public val playbackNotices: List<OpenSourceNotice> = listOf(
    OpenSourceNotice(
        name = "media3-ffmpeg-decoder",
        version = FFMPEG_DECODER_VERSION,
        license = "Apache-2.0",
        copyright = "Copyright © put.io and The Android Open Source Project",
        sourceUrl = "$FFMPEG_DECODER/tree/$FFMPEG_DECODER_TAG",
        licenseUrl = "$FFMPEG_DECODER/blob/$FFMPEG_DECODER_TAG/LICENSE",
    ),
    OpenSourceNotice(
        name = "FFmpeg",
        version = FFMPEG_VERSION,
        license = "LGPL-2.1-or-later",
        copyright = "Copyright © 2000-2026 the FFmpeg developers. " +
            "This software is based in part on the work of the Independent JPEG Group.",
        sourceUrl = "https://repo1.maven.org/maven2/io/put/media3-ffmpeg-decoder/$FFMPEG_DECODER_VERSION/" +
            "media3-ffmpeg-decoder-$FFMPEG_DECODER_VERSION-ffmpeg-source.tar.xz",
        licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html",
    ),
    OpenSourceNotice(
        name = "FFmpeg third-party notices",
        version = FFMPEG_VERSION,
        license = "Zlib, MIT, ISC, IJG, BSL-1.0, BSD-3-Clause",
        copyright = "Copyright notices of the third-party code compiled into FFmpeg",
        sourceUrl = FFMPEG_DECODER_NOTICE,
        licenseUrl = FFMPEG_DECODER_NOTICE,
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
        // Linked into ass-media's libass.so, so its source is the tree that builds that library.
        sourceUrl = "https://github.com/peerless2012/libass-android/tree/v0.5.1",
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

private const val FFMPEG_VERSION = "7.1.5"
private const val FFMPEG_DECODER_VERSION = "1.11.1+1"
private const val FFMPEG_DECODER_TAG = "v$FFMPEG_DECODER_VERSION"
private const val FFMPEG_DECODER = "https://github.com/putdotio/media3-ffmpeg-decoder"
private const val FFMPEG_DECODER_NOTICE = "$FFMPEG_DECODER/blob/$FFMPEG_DECODER_TAG/NOTICE"
private const val FONTCONFIG = "https://gitlab.freedesktop.org/fontconfig/fontconfig/-"
private const val FONTCONFIG_COMMIT = "daa175d234b8a362eedd4c18c33537cc2d19cd98"
