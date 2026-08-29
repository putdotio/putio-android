import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokenCodegenTest {

    @Test
    fun brandYellowMatchesCanonicalHex() {
        // The token graph documents hsl(44.7, 97.9%, 63.1%) as canonical #FDCE45.
        assertEquals(0xFFFDCE45L, DesignTokenCodegen.hslToArgb("hsl(44.7, 97.9%, 63.1%)"))
    }

    @Test
    fun achromaticGrays() {
        assertEquals(0xFF161616L, DesignTokenCodegen.hslToArgb("hsl(0, 0%, 8.5%)"))
        assertEquals(0xFFEDEDEDL, DesignTokenCodegen.hslToArgb("hsl(0, 0%, 93.0%)"))
        assertEquals(0xFFFFFFFFL, DesignTokenCodegen.hslToArgb("hsl(0, 0%, 100%)"))
        assertEquals(0xFF000000L, DesignTokenCodegen.hslToArgb("hsl(0, 0%, 0%)"))
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
    fun resolvesReferenceChainsAndPrefersDarkMode() {
        // In the real graph red-solid's dark token is a reference to the light one.
        val dtcg = java.io.File("../design/tokens.dtcg.json").readText()
        val code = DesignTokenCodegen.generate(dtcg, "test")
        assertTrue(code.contains("val redSolid: Color = Color(0xFFE5484D)"))
        // app-bg has distinct light/dark values; dark (8.5%) must win over light (100%).
        assertTrue(code.contains("val appBg: Color = Color(0xFF161616)"))
    }

    @Test
    fun generatesSchemeFromFullGraph() {
        val dtcg = java.io.File("../design/tokens.dtcg.json").readText()
        val code = DesignTokenCodegen.generate(dtcg, "3.0.0")
        assertTrue(code.contains("val yellowSolid: Color = Color(0xFFFDCE45)"))
        assertTrue(code.contains("val yellowTextSecondary: Color = Color(0xFFFFD147)"))
        assertTrue(code.contains("val appBg: Color = Color(0xFF161616)"))
        assertTrue(code.contains("val redSolid: Color = Color(0xFFE5484D)"))
        assertTrue(code.contains("primary = PutioDesignTokens.yellowSolid,"))
        assertTrue(code.contains("inversePrimary = PutioDesignTokens.yellowTextSecondary,"))
        assertTrue(code.contains("inverseSurface = PutioDesignTokens.componentBgActive,"))
        assertTrue(code.contains("borderVariant = PutioDesignTokens.line,"))
    }
}
