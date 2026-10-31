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
        assertTrue(code.contains("inverseSurface = PutioDesignTokens.text,"))
        assertTrue(code.contains("inverseOnSurface = PutioDesignTokens.appBg,"))
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
        // hsl(2, 100%, 77%) and hsl(151, 55%, 41.5%): the light red and green DESIGN.md names.
        assertTrue(xml.contains("<color name=\"putio_red_text_secondary\">#FFFF8E8A</color>"))
        assertTrue(xml.contains("<color name=\"putio_green_solid\">#FF30A46C</color>"))
        assertEquals(DesignTokenCodegen.RESOURCE_TOKENS.size, Regex("<color ").findAll(xml).count())
        assertTrue(!xml.contains("putio_red_solid"))
    }

    @Test
    fun designMdColorsCarryRolesAndAlphaInCssOrder() {
        val yaml = DesignTokenCodegen.generateDesignMdColors(fullGraph())
        assertTrue(yaml.startsWith("colors:\n"))
        assertTrue(yaml.contains("  primary: \"#FDCE45\" # --yellow-solid\n"))
        assertTrue(yaml.contains("  surface: \"#161616\" # --app-bg\n"))
        // 26% of 255 rounds to 0x42, written last as CSS #RRGGBBAA expects.
        assertTrue(yaml.contains("  secondary-container: \"#FDCE4542\" # --yellow-solid at 26%\n"))
        assertEquals(
            DesignTokenCodegen.M3_ROLES.size + DesignTokenCodegen.DESIGN_MD_EXTRA_COLORS.size,
            yaml.lines().count { it.startsWith("  ") },
        )
    }

    @Test
    fun designMdColorsReplaceOnlyTheirBlock() {
        val doc = "# T\n\n```yaml\ncolors:\n  primary: \"#000000\"\n```\n\n```yaml\nother: 1\n```\n"
        val updated = DesignTokenCodegen.withDesignMdColors(doc, fullGraph())
        assertTrue(updated.startsWith("# T\n\n```yaml\ncolors:\n  primary: \"#FDCE45\""))
        assertTrue(updated.endsWith("```\n\n```yaml\nother: 1\n```\n"))
        assertEquals(updated, DesignTokenCodegen.withDesignMdColors(updated, fullGraph()))
    }

    @Test
    fun designMdWithoutAColorsBlockFailsLoudly() {
        val error = runCatching { DesignTokenCodegen.withDesignMdColors("# T\n", fullGraph()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun scalesCarryDesignMdTypeAndCorners() {
        val code = DesignTokenCodegen.generateScales(File("../DESIGN.md").readText(), brandFonts(), installed = emptySet())
        assertTrue(
            code.contains(
                """
                |    public val headlineLg: TextStyle = TextStyle(
                |        fontFamily = PutioFontFamily.gtAmerica,
                |        fontWeight = FontWeight(500),
                |        fontSize = 28.sp,
                |        lineHeight = 36.sp,
                |        letterSpacing = 0.sp,
                |    )
                """.trimMargin(),
            ),
        )
        assertTrue(code.contains("        fontFamily = PutioFontFamily.berkeleyMono,\n"))
        assertTrue(code.contains("        fontFeatureSettings = \"\\\"tnum\\\"\",\n"))
        assertTrue(code.contains("    public val largeIncreased: Dp = 20.dp\n"))
        assertTrue(code.contains("    public val full: Dp = 9999.dp\n"))
    }

    @Test
    fun installedFamiliesEmbedTheirLockedFacesAndOthersUseTheSystemFace() {
        val lock = brandFonts()
        val gtAmerica = lock.faces.filter { it.family == "GT America" }.map { it.file }.toSet()
        val code = DesignTokenCodegen.generateScales(File("../DESIGN.md").readText(), lock, gtAmerica)
        assertTrue(
            code.contains(
                """
                |    val gtAmerica: FontFamily = FontFamily(
                |        Font(R.font.gt_america_standard_regular, FontWeight(400)),
                |        Font(R.font.gt_america_standard_medium, FontWeight(500)),
                |    )
                """.trimMargin(),
            ),
        )
        assertTrue(code.contains("    val berkeleyMono: FontFamily = FontFamily.Monospace\n"))
        // A family embeds only whole: one missing face sends it all to the system face.
        val partial = DesignTokenCodegen.generateScales(
            File("../DESIGN.md").readText(),
            lock,
            setOf("gt_america_standard_regular.ttf", "berkeley_mono_variable.ttf"),
        )
        assertTrue(partial.contains("    val gtAmerica: FontFamily = FontFamily.Default\n"))
        assertTrue(
            partial.contains(
                """
                |    val berkeleyMono: FontFamily = FontFamily(
                |        Font(
                |            R.font.berkeley_mono_variable,
                |            FontWeight(400),
                |            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
                |        ),
                |    )
                """.trimMargin(),
            ),
        )
    }

    @Test
    fun theLockHoldsExactlyTheFacesDesignMdSets() {
        val doc = "---\ntypography:\n  body:\n    fontFamily: GT America\n    fontSize: 16px\n" +
            "    fontWeight: 400\n    lineHeight: 22px\nrounded:\n  small: 8px\n---\n"
        val regular = BrandFonts.Face("gt_america_regular.ttf", "GT America", "0".repeat(64), weight = 400)
        val bold = BrandFonts.Face("gt_america_bold.ttf", "GT America", "0".repeat(64), weight = 700)
        val variable = BrandFonts.Face("gt_america_variable.ttf", "GT America", "0".repeat(64), weightRange = 100..900)
        assertTrue(DesignTokenCodegen.generateScales(doc, BrandFonts.Lock("fonts", listOf(regular)), emptySet()).isNotEmpty())
        assertTrue(DesignTokenCodegen.generateScales(doc, BrandFonts.Lock("fonts", listOf(variable)), emptySet()).isNotEmpty())
        listOf(
            listOf(regular, bold) to "gt_america_bold.ttf",
            listOf(bold) to "weight 400",
        ).forEach { (faces, expected) ->
            val error = runCatching { DesignTokenCodegen.generateScales(doc, BrandFonts.Lock("fonts", faces), emptySet()) }
                .exceptionOrNull()
            assertTrue(expected, error is IllegalStateException && error.message!!.contains(expected))
        }
    }

    @Test
    fun scalesRejectWhatTheyCannotModel() {
        val base = "---\ntypography:\n  body:\n    fontFamily: GT America\n    fontSize: 16px\n" +
            "    fontWeight: 400\n    lineHeight: 22px\nrounded:\n  small: 8px\n---\n"
        val lock = BrandFonts.Lock(
            "fonts",
            listOf(BrandFonts.Face("gt_america_regular.ttf", "GT America", "0".repeat(64), weight = 400)),
        )
        assertTrue(DesignTokenCodegen.generateScales(base, lock, emptySet()).contains("public val small: Dp = 8.dp"))
        listOf(
            base.replace("GT America", "Comic Sans") to "Comic Sans",
            base.replace("16px", "1rem") to "fontSize",
            base.replace("    lineHeight: 22px\n", "    lineHeight: 22px\n    case: upper\n") to "case",
            base.replace("rounded:\n  small: 8px\n", "") to "rounded",
        ).forEach { (doc, expected) ->
            val error = runCatching { DesignTokenCodegen.generateScales(doc, lock, emptySet()) }.exceptionOrNull()
            assertTrue(expected, error is IllegalStateException && error.message!!.contains(expected))
        }
    }

    @Test
    fun frontMatterReadsNestedMapsAndQuotedScalars() {
        val front = DesignTokenCodegen.frontMatter(
            "---\nname: \"put.io\"\na:\n  b:\n    c: '\"tnum\"'\n  d: 2px\ne: 1\n---\n# Body\nf: 3\n",
        )
        assertEquals(
            mapOf("name" to "put.io", "a" to mapOf("b" to mapOf("c" to "\"tnum\""), "d" to "2px"), "e" to "1"),
            front,
        )
    }

    private fun fullGraph() = File("../design/tokens.dtcg.json").readText()

    private fun brandFonts() = BrandFonts.parseLock(File("../design/brand-fonts.lock.json").readText())
}
