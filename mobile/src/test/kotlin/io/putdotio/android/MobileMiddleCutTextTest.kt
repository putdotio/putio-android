package io.putdotio.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One unit of width per UTF-16 code unit, so each case states its own budget. */
class MobileMiddleCutTextTest {
    private val width: (String) -> Int = { it.length }

    @Test
    fun aNameThatFitsStaysWhole() {
        assertEquals("Sintel.mkv", middleCut("Sintel.mkv", 10, width))
    }

    @Test
    fun aLongNameKeepsItsStartAndItsLastElevenCharacters() {
        assertEquals("Blue.Lights.S04E01…ORGANiC.mkv", middleCut(NAME, 30, width))
    }

    @Test
    fun aNarrowRowKeepsAShorterTailRatherThanLoseTheExtension() {
        assertEquals("…ANiC.mkv", middleCut(NAME, 9, width))
    }

    @Test
    fun noCutSplitsAnEmoji() {
        // The last eleven UTF-16 units start inside the first tree.
        val name = "Holiday 🎄 clips 🎄🎄🎄🎄.mp4"
        for (budget in 4..name.length) {
            val cut = middleCut(name, budget, width)
            assertTrue(cut, cut.indices.all { index -> cut.pairedAt(index) })
        }
    }

    private fun String.pairedAt(index: Int): Boolean = when {
        this[index].isHighSurrogate() -> index + 1 < length && this[index + 1].isLowSurrogate()
        this[index].isLowSurrogate() -> index > 0 && this[index - 1].isHighSurrogate()
        else -> true
    }

    private companion object {
        const val NAME = "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv"
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileMiddleCutTextLinesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twoLinesWrapANameAndCutOnlyWhatStillDoesNotFit() {
        val fits = "FFTT Tree Rings October 23, 2026.mp3"
        val tooLong = "FFTT Tree Rings October 23, 2026, the long uncut interview with everyone who was there.mp3"
        compose.setContent {
            PutioTheme {
                Column(Modifier.width(240.dp)) {
                    MobileMiddleCutText(fits)
                    MobileMiddleCutText(fits, maxLines = 2)
                    MobileMiddleCutText(tooLong, maxLines = 2)
                }
            }
        }

        val nodes = compose.onAllNodes(hasText(fits)).fetchSemanticsNodes()
        assertEquals("One line cuts the name, two lines hold it whole", 1, nodes.count { MiddleCutShown in it.config })
        val cut = compose.onNode(hasText(tooLong)).fetchSemanticsNode().config[MiddleCutShown]
        assertTrue(cut, cut.contains("…") && cut.endsWith(tooLong.takeLast(MIDDLE_CUT_TAIL)))
    }
}
