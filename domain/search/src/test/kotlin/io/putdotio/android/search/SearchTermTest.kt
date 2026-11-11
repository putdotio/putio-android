package io.putdotio.android.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTermTest {
    @Test
    fun showsATermOfFiftyCharactersWhole() {
        val term = SearchTerm("a".repeat(50))

        assertEquals("a".repeat(50), term.shown)
    }

    @Test
    fun cutsALongerTermAtFiftyCharactersWithAnEllipsisAndKeepsTheFullValue() {
        val full = "b".repeat(200)
        val term = SearchTerm(full)

        assertEquals("b".repeat(50) + "…", term.shown)
        assertEquals(full, term.value)
    }

    @Test
    fun neverSplitsACharacterOutsideTheBasicPlane() {
        val term = SearchTerm("c".repeat(49) + "🎬" + "dd")

        assertEquals("c".repeat(49) + "🎬…", term.shown)
    }

    @Test
    fun dropsASpaceLeftBeforeTheEllipsis() {
        val term = SearchTerm("e".repeat(49) + " tears of steel")

        assertEquals("e".repeat(49) + "…", term.shown)
    }
}
