package io.putdotio.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileMiddleCutTest {
    @Test
    fun keepsTheStartAndSevenCharactersBeforeTheExtension() {
        // DESIGN.md's example, at a width that holds 30 characters.
        assertEquals(
            "Blue.Lights.S04E01…ORGANiC.mkv",
            middleCut("Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv") { it.length <= 30 },
        )
        assertEquals(
            "FFTT Tree Rings Oc…3, 2026.mp3",
            middleCut("FFTT Tree Rings October 23, 2026.mp3") { it.length <= 30 },
        )
    }

    @Test
    fun aNameThatFitsOrHasNoExtensionStaysReadable() {
        assertEquals("Sintel.2010.1080p.mkv", middleCut("Sintel.2010.1080p.mkv") { it.length <= 30 })
        assertEquals("Items shared…friends", middleCut("Items shared with you, from friends") { it.length <= 20 })
        // An emoji on either cut is kept or dropped whole, never split into half a surrogate pair.
        assertEquals(
            "ab…😀abcdef.mkv",
            middleCut("ab😀xyz😀abcdef.mkv") { it.length <= 16 },
        )
    }
}
