import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokenCodegenTest {
    @Test
    fun hslMatchesCanonicalHex() {
        listOf(
            // The token graph documents hsl(44.7, 97.9%, 63.1%) as canonical #FDCE45.
            "hsl(44.7, 97.9%, 63.1%)" to 0xFFFDCE45L,
            "hsl(0, 0%, 8.5%)" to 0xFF161616L,
            "hsl(0, 0%, 93.0%)" to 0xFFEDEDEDL,
            "hsl(0, 0%, 100%)" to 0xFFFFFFFFL,
            "hsl(0, 0%, 0%)" to 0xFF000000L,
        ).forEach { (hsl, argb) -> assertEquals(hsl, argb, DesignTokenCodegen.hslToArgb(hsl)) }
    }

    @Test
    fun compositeTokensAreRejected() {
        val error =
            runCatching { DesignTokenCodegen.hslToArgb("0 1px 2px hsla(0, 0%, 0%, 0.05)") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun hslaCarriesAlpha() {
        assertEquals(0x66000000L, DesignTokenCodegen.hslToArgb("hsla(0, 0%, 0%, 0.4)"))
        assertEquals(0xFFFFFFFFL, DesignTokenCodegen.hslToArgb("hsla(0, 0%, 100%, 1)"))
    }

    @Test
    fun missingTokenFailsLoudly() {
        val dtcg = """
        {
          "color": {
            "brand": {
              "yellow": {
                "${'$'}type": "color", "${'$'}value": "hsl(44.7, 97.9%, 63.1%)",
                "${'$'}extensions": {"putio": {"cssName": "yellow-solid", "mode": "global"}}
              }
            }
          }
        }
        """.trimIndent()
        val result = runCatching { DesignTokenCodegen.generate(dtcg, "test") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("primary-foreground"))
    }

    @Test
    fun generatesSchemeFromFullGraph() {
        val code = DesignTokenCodegen.generate(fullGraph(), "3.3.0")
        assertTrue(code.contains("public val yellowSolid: Color = Color(0xFFFDCE45)"))
        assertTrue(code.contains("internal val yellowTextSecondary: Color = Color(0xFFFFD147)"))
        // app-bg has distinct light/dark values; dark (8.5%) must win over light (100%).
        assertTrue(code.contains("internal val appBg: Color = Color(0xFF161616)"))
        // red-solid's dark token is a reference to the light one.
        assertTrue(code.contains("internal val redSolid: Color = Color(0xFFE5484D)"))
        assertTrue(code.contains("internal fun putioDarkColorScheme(): androidx.compose.material3.ColorScheme ="))
        assertTrue(code.contains("public fun putioTvDarkColorScheme(): androidx.tv.material3.ColorScheme ="))
        assertTrue(code.contains("primary = PutioDesignTokens.yellowSolid,"))
        assertTrue(code.contains("inversePrimary = PutioDesignTokens.yellowTextSecondary,"))
        assertTrue(code.contains("inverseSurface = PutioDesignTokens.componentBgActive,"))
        assertTrue(code.contains("borderVariant = PutioDesignTokens.line,"))
    }

    @Test
    fun generatesTvOverscanRatiosFromFullGraph() {
        val code = DesignTokenCodegen.generate(fullGraph(), "3.3.0")
        assertTrue(code.contains("public const val tvOverscanX: Float = 0.04f"))
        assertTrue(code.contains("public const val tvOverscanY: Float = 0.02f"))
    }

    @Test
    fun overscanOnTheWrongAxisFailsLoudly() {
        val dtcg = fullGraph()
            .replace("\"basis\": \"viewport-width\"", "\"basis\": \"viewport-height\"")
        val error = runCatching { DesignTokenCodegen.generate(dtcg, "test") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("tv-overscan-x"))
    }

    @Test
    fun missingOverscanFailsLoudly() {
        val dtcg = fullGraph()
            .replace("\"tv-overscan-y\"", "\"tv-overscan-renamed\"")
        val error = runCatching { DesignTokenCodegen.generate(dtcg, "test") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("tv-overscan-y"))
    }

    @Test
    fun generatesXmlColorsForTheResourceTokensOnly() {
        val xml = DesignTokenCodegen.generateColorResources(fullGraph(), "3.3.0")
        assertTrue(xml.contains("<color name=\"putio_yellow_solid\">#FFFDCE45</color>"))
        // Dark mode wins here too.
        assertTrue(xml.contains("<color name=\"putio_app_bg\">#FF161616</color>"))
        assertEquals(DesignTokenCodegen.RESOURCE_TOKENS.size, Regex("<color ").findAll(xml).count())
        assertTrue(!xml.contains("putio_red_solid"))
    }

    private fun fullGraph() = File("../design/tokens.dtcg.json").readText()
}
