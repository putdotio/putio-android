import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates the put.io Compose color schemes and TV safe-area ratios from the
 * vendored DTCG token graph (`design/tokens.dtcg.json`, from @putdotio/design),
 * and the type and corner scales from the root DESIGN.md's front matter.
 * The M3-role map below is the tier-2 binding contract; the root DESIGN.md
 * carries it as a generated `colors` block. The app never hand-writes a design value.
 */
object DesignTokenCodegen {

    /** Kotlin property name → DTCG `$extensions.putio.cssName`. */
    val TOKEN_BINDINGS: List<Pair<String, String>> = listOf(
        "yellowSolid" to "yellow-solid",
        "primaryForeground" to "primary-foreground",
        "yellowTextSecondary" to "yellow-text-secondary",
        "appBg" to "app-bg",
        "text" to "text",
        "textSecondary" to "text-secondary",
        "componentBg" to "component-bg",
        "componentBgHover" to "component-bg-hover",
        "componentBgActive" to "component-bg-active",
        "border" to "border",
        "line" to "line",
        "redSolid" to "red-solid",
        "redTextSecondary" to "red-text-secondary",
        "greenSolid" to "green-solid",
        "solid" to "solid",
    )

    /**
     * Token properties the apps read directly, so the generated object makes them `public`. Every
     * other color reaches the apps only through the color schemes and stays `internal` to
     * `core/design`. The overscan ratios are always public: the TV shell pads by them.
     */
    val APP_TOKENS: Set<String> = setOf("yellowSolid", "redTextSecondary", "greenSolid")

    /**
     * Tokens Android XML reads as `@color/putio_<css-name>`: RemoteViews layouts, which Compose
     * colors cannot reach, and adaptive shortcut icons. Add a token here before an XML surface uses it.
     */
    val RESOURCE_TOKENS: Set<String> =
        setOf("appBg", "componentBg", "text", "textSecondary", "yellowSolid", "redTextSecondary", "greenSolid")

    /**
     * M3 role → token property (optionally `property:alpha`). These are the
     * contracted roles from DESIGN.md. The container and background entries
     * keep stock components from falling back to M3 baseline purple:
     * background/onBackground mirror surface/onSurface;
     * primaryContainer/onPrimaryContainer carry the FAB spec from the
     * android-s00-shell card (--yellow-solid / --primary-foreground);
     * secondary/secondaryContainer/onSecondaryContainer carry the card's nav
     * label and indicator; inverse roles carry the snackbar contract;
     * surfaceContainerLow carries the stock bottom-sheet surface; and onError
     * carries the destructive button's dark label on the `error` fill.
     */
    val M3_ROLES: List<Pair<String, String>> = listOf(
        "primary" to "yellowSolid",
        "onPrimary" to "primaryForeground",
        "primaryContainer" to "yellowSolid",
        "onPrimaryContainer" to "primaryForeground",
        "secondary" to "text",
        "secondaryContainer" to "yellowSolid:0.26",
        "onSecondaryContainer" to "yellowSolid",
        "background" to "appBg",
        "onBackground" to "text",
        "surface" to "appBg",
        "onSurface" to "text",
        "onSurfaceVariant" to "textSecondary",
        "surfaceContainerLow" to "componentBg",
        "surfaceContainer" to "componentBg",
        "surfaceContainerHigh" to "componentBgHover",
        "surfaceContainerHighest" to "componentBgActive",
        "inverseSurface" to "componentBgActive",
        "inverseOnSurface" to "text",
        "inversePrimary" to "yellowTextSecondary",
        "outline" to "border",
        "outlineVariant" to "line",
        "error" to "redSolid",
        "onError" to "appBg",
    )

    /**
     * androidx.tv.material3 role → token property. The TV ColorScheme predates
     * the surfaceContainer tiers and names its outline roles border/borderVariant.
     * Its stock components still read the container and inverse roles: notably,
     * a focused ListItem uses inverseSurface.
     */
    val TV_ROLES: List<Pair<String, String>> = listOf(
        "primary" to "yellowSolid",
        "onPrimary" to "primaryForeground",
        "primaryContainer" to "yellowSolid",
        "onPrimaryContainer" to "primaryForeground",
        "inversePrimary" to "yellowTextSecondary",
        "secondary" to "text",
        "secondaryContainer" to "yellowSolid:0.26",
        "onSecondaryContainer" to "yellowSolid",
        "background" to "appBg",
        "onBackground" to "text",
        "surface" to "appBg",
        "onSurface" to "text",
        "surfaceVariant" to "componentBg",
        "onSurfaceVariant" to "textSecondary",
        "inverseSurface" to "componentBgActive",
        "inverseOnSurface" to "text",
        "border" to "border",
        "borderVariant" to "line",
        "error" to "redSolid",
    )

    /** Token cssNames DESIGN.md lists after the M3 roles: put.io colors with no M3 role. */
    val DESIGN_MD_EXTRA_COLORS: List<String> = listOf("red-text-secondary", "green-solid", "solid")

    /** A unitless `tv` token that platform adapters multiply by one viewport axis. */
    data class ViewportRatioBinding(val property: String, val cssName: String, val basis: String)

    /** The TV overscan safe area; the shell pads each viewport axis by these fractions. */
    val VIEWPORT_RATIO_BINDINGS: List<ViewportRatioBinding> = listOf(
        ViewportRatioBinding("tvOverscanX", "tv-overscan-x", basis = "viewport-width"),
        ViewportRatioBinding("tvOverscanY", "tv-overscan-y", basis = "viewport-height"),
    )

    private data class RawToken(
        val value: String,
        val type: String?,
        val cssName: String?,
        val mode: String?,
        val basis: String?,
    )

    fun generate(dtcgJson: String, designVersion: String): String {
        val tokens = parse(dtcgJson)
        val resolved = TOKEN_BINDINGS.associate { (property, cssName) ->
            property to resolve(tokens, cssName)
        }
        val ratios = VIEWPORT_RATIO_BINDINGS.associate { it.property to resolveViewportRatio(tokens, it) }
        return render(resolved, ratios, designVersion)
    }

    /** `values/putio_design_colors.xml`: the [RESOURCE_TOKENS] colors, resolved like the Kotlin ones. */
    fun generateColorResources(dtcgJson: String, designVersion: String): String {
        val tokens = parse(dtcgJson)
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<!-- Generated by :core:design:generateDesignTokens from design/tokens.dtcg.json")
            appendLine("     (@putdotio/design $designVersion). Do not edit; see design/README.md. -->")
            appendLine("<resources>")
            for ((property, cssName) in TOKEN_BINDINGS) {
                if (property !in RESOURCE_TOKENS) continue
                val argb = resolve(tokens, cssName)
                appendLine("    <color name=\"putio_${cssName.replace('-', '_')}\">#${"%08X".format(argb)}</color>")
            }
            appendLine("</resources>")
        }
    }

    /** DESIGN.md's `colors` YAML: each M3 role kebab-cased, then [DESIGN_MD_EXTRA_COLORS], each naming its token. */
    fun generateDesignMdColors(dtcgJson: String): String {
        val tokens = parse(dtcgJson)
        val cssNames = TOKEN_BINDINGS.toMap()
        return buildString {
            appendLine("colors:")
            for ((role, spec) in M3_ROLES) {
                val (property, alpha) = spec.split(":").let { it[0] to it.getOrNull(1) }
                val cssName = cssNames.getValue(property)
                var argb = resolve(tokens, cssName)
                if (alpha != null) argb = (Math.round(alpha.toDouble() * 255.0) shl 24) or (argb and 0xFFFFFFL)
                val note = if (alpha == null) "--$cssName" else "--$cssName at ${Math.round(alpha.toDouble() * 100)}%"
                appendLine("  ${role.kebab()}: \"${yamlHex(argb)}\" # $note")
            }
            for (cssName in DESIGN_MD_EXTRA_COLORS) {
                appendLine("  $cssName: \"${yamlHex(resolve(tokens, cssName))}\" # --$cssName")
            }
        }
    }

    /** [designMd] with its one ```yaml block that starts with `colors:` replaced by [generateDesignMdColors]. */
    fun withDesignMdColors(designMd: String, dtcgJson: String): String {
        val block = Regex("""(?m)^```yaml\n(colors:\n(?:(?!```).*\n)*)```$""")
        val matches = block.findAll(designMd).toList()
        check(matches.size == 1) { "DESIGN.md needs exactly one ```yaml block starting with `colors:`, found ${matches.size}" }
        val range = matches.single().groups[1]!!.range
        return designMd.replaceRange(range, generateDesignMdColors(dtcgJson))
    }

    /** The system face a DESIGN.md family falls back to when the build lacks its licensed files. */
    val SYSTEM_FACES: Map<String, String> = mapOf(
        "GT America" to "FontFamily.Default",
        "Berkeley Mono" to "FontFamily.Monospace",
    )

    private val TYPOGRAPHY_KEYS =
        setOf("fontFamily", "fontSize", "fontWeight", "lineHeight", "letterSpacing", "fontFeature")

    /** A DESIGN.md family: its generated property, its locked faces, and whether all of them are installed. */
    private data class Family(
        val name: String,
        val property: String,
        val faces: List<Pair<Int, BrandFonts.Face>>,
        val embedded: Boolean,
    )

    /**
     * Each family DESIGN.md's typography names, with the locked face for every weight it sets. A
     * weight no locked face covers fails, and so does a locked face DESIGN.md never uses: the lock
     * holds exactly the faces the contract sets. A family embeds only when all its faces are installed.
     */
    private fun families(typography: Map<*, *>, fonts: BrandFonts.Lock, installed: Set<String>): List<Family> {
        val weights = linkedMapOf<String, MutableSet<Int>>()
        for ((name, value) in typography) {
            val style = value as? Map<*, *> ?: error("typography.$name must be a map")
            val family = style["fontFamily"] as? String ?: error("typography.$name needs fontFamily")
            check(family in SYSTEM_FACES) { "typography.$name uses '$family', which has no Android system fallback" }
            weights.getOrPut(family) { sortedSetOf() } += weight(style, "$name")
        }
        val families = weights.map { (family, used) ->
            val faces = used.map { weight ->
                val candidates = fonts.faces.filter { it.family == family }
                val face = candidates.firstOrNull { it.weight == weight } ?: candidates.firstOrNull { it.covers(weight) }
                    ?: error("DESIGN.md sets $family at weight $weight, which design/brand-fonts.lock.json has no face for")
                weight to face
            }
            val property = family.lowercase().replace(Regex("[^a-z0-9]+"), "-").camel()
            Family(family, property, faces, faces.all { (_, face) -> face.file in installed })
        }
        val used = families.flatMap { family -> family.faces.map { (_, face) -> face.file } }.toSet()
        val unused = fonts.faces.map { it.file } - used
        check(unused.isEmpty()) {
            "design/brand-fonts.lock.json locks ${unused.joinToString()}, which DESIGN.md's typography never sets"
        }
        return families
    }

    private fun weight(style: Map<*, *>, name: String): Int =
        (style["fontWeight"] as? String)?.toIntOrNull()?.takeIf { it in 1..1000 }
            ?: error("typography.$name.fontWeight must be 1..1000")

    /**
     * `PutioScales.kt`: DESIGN.md's `typography` front matter as public `TextStyle`s and its
     * `rounded` front matter as public `Dp` radii, plus `PutioFontFamily`, its font families. A
     * family embeds the locked faces in [installed] (file names from [fonts]) and otherwise falls
     * back to [SYSTEM_FACES]. DESIGN.md owns these values, not the token graph, so a change lands
     * there and regenerates here.
     */
    fun generateScales(designMd: String, fonts: BrandFonts.Lock, installed: Set<String>): String {
        val front = frontMatter(designMd)
        val typography = front.section("typography")
        val rounded = front.section("rounded")
        val families = families(typography, fonts, installed)
        return buildString {
            appendLine("// Generated by :core:design:generateDesignTokens from DESIGN.md's front matter")
            appendLine("// and design/brand-fonts.lock.json. Do not edit; see design/README.md.")
            appendLine("package io.putdotio.android.design")
            appendLine()
            appendLine("import androidx.compose.ui.text.TextStyle")
            if (families.any { it.embedded }) appendLine("import androidx.compose.ui.text.font.Font")
            appendLine("import androidx.compose.ui.text.font.FontFamily")
            appendLine("import androidx.compose.ui.text.font.FontWeight")
            appendLine("import androidx.compose.ui.unit.Dp")
            appendLine("import androidx.compose.ui.unit.dp")
            appendLine("import androidx.compose.ui.unit.em")
            appendLine("import androidx.compose.ui.unit.sp")
            appendLine()
            appendLine("/**")
            appendLine(" * DESIGN.md's font families. A family this build lacks the licensed files for falls back to")
            appendLine(" * the system face; scripts/sync-brand-fonts.sh fetches them. A variable face takes its weight")
            appendLine(" * axis from the requested weight.")
            appendLine(" */")
            appendLine("internal object PutioFontFamily {")
            for (family in families) {
                if (family.embedded) {
                    appendLine("    /** ${family.name}: the licensed faces. */")
                    appendLine("    val ${family.property}: FontFamily = FontFamily(")
                    for ((weight, face) in family.faces) {
                        appendLine("        Font(R.font.${face.file.substringBeforeLast('.')}, FontWeight($weight)),")
                    }
                    appendLine("    )")
                } else {
                    appendLine("    /** ${family.name}: the system face; this build lacks the licensed files. */")
                    appendLine("    val ${family.property}: FontFamily = ${SYSTEM_FACES.getValue(family.name)}")
                }
            }
            appendLine("}")
            appendLine()
            appendLine("/** DESIGN.md's type scale, named after its tokens. [PutioTheme] maps it onto Material slots. */")
            appendLine("public object PutioTypeScale {")
            for ((name, value) in typography) {
                val style = value as Map<*, *>
                val family = families.single { it.name == style["fontFamily"] }
                appendTextStyle("$name", style, family)
            }
            appendLine("}")
            appendLine()
            appendLine("/** DESIGN.md's corner radii, named after its tokens. */")
            appendLine("public object PutioRadius {")
            for ((name, value) in rounded) {
                appendLine("    /** rounded.$name */")
                appendLine("    public val ${"$name".camel()}: Dp = ${pixels(value, "rounded.$name")}.dp")
            }
            appendLine("}")
        }
    }

    private fun StringBuilder.appendTextStyle(name: String, style: Map<*, *>, family: Family) {
        val unknown = style.keys.map { it.toString() } - TYPOGRAPHY_KEYS
        check(unknown.isEmpty()) { "typography.$name has unsupported keys $unknown" }
        fun required(key: String): String = style[key] as? String ?: error("typography.$name needs $key")
        val weight = weight(style, name)
        val size = pixels(required("fontSize"), "typography.$name.fontSize")
        val lineHeight = pixels(required("lineHeight"), "typography.$name.lineHeight")
        val letterSpacing = (style["letterSpacing"] as String?)?.let { spacing ->
            if (spacing.endsWith("em")) {
                "${number(spacing.removeSuffix("em"), "typography.$name.letterSpacing")}.em"
            } else {
                "${pixels(spacing, "typography.$name.letterSpacing")}.sp"
            }
        } ?: "0.sp"
        appendLine("    /** $name: ${family.name}, $size/$lineHeight, weight $weight */")
        appendLine("    public val ${name.camel()}: TextStyle = TextStyle(")
        appendLine("        fontFamily = PutioFontFamily.${family.property},")
        appendLine("        fontWeight = FontWeight($weight),")
        appendLine("        fontSize = $size.sp,")
        appendLine("        lineHeight = $lineHeight.sp,")
        appendLine("        letterSpacing = $letterSpacing,")
        (style["fontFeature"] as String?)?.let { feature ->
            check(Regex("""^[A-Za-z0-9"' ,]+$""").matches(feature)) {
                "typography.$name.fontFeature has unsupported characters: '$feature'"
            }
            appendLine("        fontFeatureSettings = \"${feature.replace("\"", "\\\"")}\",")
        }
        appendLine("    )")
    }

    /** `28px` → `28`; DESIGN.md writes dp and sp as `px` because the format has no Android units. */
    private fun pixels(value: Any?, path: String): String {
        val text = value as? String ?: error("$path must be a scalar")
        check(text.endsWith("px")) { "$path must be in px, got '$text'" }
        return number(text.removeSuffix("px"), path)
    }

    private fun number(text: String, path: String): String {
        val number = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("$path is not a number: '$text'")
        return if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    }

    private fun Map<String, Any>.section(name: String): Map<*, *> =
        (this[name] as? Map<*, *>)?.takeIf { it.isNotEmpty() } ?: error("DESIGN.md front matter has no $name section")

    /**
     * The YAML front matter between the leading `---` lines, for the subset DESIGN.md uses:
     * nested `key:` maps and `key: value` scalars, optionally quoted once. Anything else fails loudly.
     */
    fun frontMatter(designMd: String): Map<String, Any> {
        val lines = designMd.lines()
        check(lines.firstOrNull() == "---") { "DESIGN.md must start with YAML front matter" }
        val end = lines.subList(1, lines.size).indexOf("---") + 1
        check(end > 0) { "DESIGN.md front matter is not closed" }
        val root = linkedMapOf<String, Any>()
        val open = ArrayDeque<Pair<Int, MutableMap<String, Any>>>().apply { add(-1 to root) }
        val entry = Regex("""^([A-Za-z0-9_-]+):(?:\s+(.*))?$""")
        for (line in lines.subList(1, end)) {
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val match = entry.matchEntire(line.trim()) ?: error("unsupported DESIGN.md front matter line: '$line'")
            while (open.last().first >= indent) open.removeLast()
            val key = match.groupValues[1]
            val raw = match.groupValues[2]
            val parent = open.last().second
            check(key !in parent) { "DESIGN.md front matter repeats '$key'" }
            if (raw.isEmpty()) {
                val child = linkedMapOf<String, Any>()
                parent[key] = child
                open.add(indent to child)
            } else {
                parent[key] = unquote(raw)
            }
        }
        return root
    }

    private fun unquote(raw: String): String =
        if (raw.length >= 2 && (raw.first() == '"' || raw.first() == '\'') && raw.last() == raw.first()) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }

    private fun String.camel(): String = replace(Regex("-([a-z0-9])")) { it.groupValues[1].uppercase() }

    private fun String.kebab(): String = replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

    /** `#RRGGBB`, or `#RRGGBBAA` when translucent: the CSS order DESIGN.md uses, not Android's ARGB. */
    private fun yamlHex(argb: Long): String {
        val rgb = "%06X".format(argb and 0xFFFFFFL)
        val alpha = (argb ushr 24) and 0xFFL
        return if (alpha == 0xFFL) "#$rgb" else "#$rgb${"%02X".format(alpha)}"
    }

    private fun parse(dtcgJson: String): Map<String, RawToken> {
        val root = Json.parseToJsonElement(dtcgJson).jsonObject
        val out = mutableMapOf<String, RawToken>()
        fun walk(obj: JsonObject, path: String) {
            for ((key, element) in obj) {
                if (key.startsWith("$")) continue
                val child = element as? JsonObject ?: continue
                val childPath = if (path.isEmpty()) key else "$path.$key"
                val value = child["\$value"]
                if (value is JsonPrimitive) {
                    val putio = child["\$extensions"]?.jsonObject?.get("putio")?.jsonObject
                    out[childPath] = RawToken(
                        value = value.content,
                        type = child["\$type"]?.jsonPrimitive?.content,
                        cssName = putio?.get("cssName")?.jsonPrimitive?.content,
                        mode = putio?.get("mode")?.jsonPrimitive?.content,
                        basis = putio?.get("basis")?.jsonPrimitive?.content,
                    )
                } else {
                    walk(child, childPath)
                }
            }
        }
        walk(root, "")
        return out
    }

    /** Dark mode wins; `global` tokens are mode-independent. Light-only is an error: the binding is dark only. */
    private fun resolve(tokens: Map<String, RawToken>, cssName: String): Long {
        val candidates = tokens.values.filter { it.cssName == cssName }
        val token = candidates.firstOrNull { it.mode == "dark" }
            ?: candidates.firstOrNull { it.mode == "global" }
            ?: error("token graph has no dark or global token for cssName '$cssName'")
        return hslToArgb(dereference(tokens, token.value, depth = 0))
    }

    /** A ratio of the wrong axis or outside [0, 0.5) would pad the wrong edge or swallow the viewport. */
    private fun resolveViewportRatio(tokens: Map<String, RawToken>, binding: ViewportRatioBinding): Double {
        val token = tokens.values.firstOrNull { it.cssName == binding.cssName && it.mode == "tv" }
            ?: error("token graph has no tv token for cssName '${binding.cssName}'")
        check(token.type == "number") { "'${binding.cssName}' must be a number token, got ${token.type}" }
        check(token.basis == binding.basis) {
            "'${binding.cssName}' must be based on ${binding.basis}, got ${token.basis}"
        }
        val ratio = dereference(tokens, token.value, depth = 0).toDoubleOrNull()
            ?: error("'${binding.cssName}' is not a number: '${token.value}'")
        check(ratio >= 0.0 && ratio < 0.5) { "'${binding.cssName}' must be a viewport fraction in [0, 0.5), got $ratio" }
        return ratio
    }

    private fun dereference(tokens: Map<String, RawToken>, value: String, depth: Int): String {
        require(depth < 10) { "token reference chain too deep at '$value'" }
        val match = Regex("""^\{([^}]+)}$""").find(value.trim()) ?: return value
        val target = tokens[match.groupValues[1]]
            ?: error("unresolvable token reference '$value'")
        return dereference(tokens, target.value, depth + 1)
    }

    fun hslToArgb(hsl: String): Long {
        val match =
            Regex("""hsla?\(\s*([0-9.]+)\s*,\s*([0-9.]+)%\s*,\s*([0-9.]+)%\s*(?:,\s*([0-9.]+)\s*)?\)""")
                .matchEntire(hsl.trim())
                ?: error("expected an hsl() or hsla() color, got '$hsl'")
        val (h, s, l) = match.groupValues.drop(1).take(3).map { it.toDouble() }
        val alpha = match.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble() ?: 1.0
        val c = (1.0 - kotlin.math.abs(2.0 * l / 100.0 - 1.0)) * (s / 100.0)
        val hp = (h % 360.0) / 60.0
        val x = c * (1.0 - kotlin.math.abs(hp % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hp < 1.0 -> Triple(c, x, 0.0)
            hp < 2.0 -> Triple(x, c, 0.0)
            hp < 3.0 -> Triple(0.0, c, x)
            hp < 4.0 -> Triple(0.0, x, c)
            hp < 5.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l / 100.0 - c / 2.0
        fun channel(v: Double): Long = Math.round(v * 255.0).coerceIn(0L, 255L)
        return (channel(alpha) shl 24) or (channel(r1 + m) shl 16) or (channel(g1 + m) shl 8) or channel(b1 + m)
    }

    private fun render(
        resolved: Map<String, Long>,
        ratios: Map<String, Double>,
        designVersion: String,
    ): String = buildString {
        appendLine("// Generated by :core:design:generateDesignTokens from design/tokens.dtcg.json")
        appendLine("// (@putdotio/design $designVersion). Do not edit; see design/README.md.")
        appendLine("package io.putdotio.android.design")
        appendLine()
        appendLine("import androidx.compose.ui.graphics.Color")
        appendLine()
        appendLine("/** Dark-mode put.io tokens. Property names mirror the token graph cssNames. */")
        appendLine("public object PutioDesignTokens {")
        for ((property, cssName) in TOKEN_BINDINGS) {
            val argb = resolved.getValue(property)
            val visibility = if (property in APP_TOKENS) "public" else "internal"
            appendLine("    /** --$cssName */")
            appendLine("    $visibility val $property: Color = Color(0x${"%08X".format(argb)})")
        }
        for (binding in VIEWPORT_RATIO_BINDINGS) {
            appendLine()
            appendLine("    /** --${binding.cssName}: fraction of the ${binding.basis.removePrefix("viewport-")} kept clear on each edge. */")
            appendLine("    public const val ${binding.property}: Float = ${ratios.getValue(binding.property)}f")
        }
        appendLine("}")
        appendLine()
        // PutioTheme applies the mobile scheme; the TV app builds its own MaterialTheme from the TV one.
        appendLine("/** The tier-2 binding: one dark scheme, every component stock Material 3. */")
        appendLine("internal fun putioDarkColorScheme(): androidx.compose.material3.ColorScheme =")
        appendLine("    androidx.compose.material3.darkColorScheme(")
        for ((role, spec) in M3_ROLES) {
            appendLine("        $role = ${renderSpec(spec)},")
        }
        appendLine("    )")
        appendLine()
        appendLine("/** Same contract projected onto the Compose for TV color scheme. */")
        appendLine("public fun putioTvDarkColorScheme(): androidx.tv.material3.ColorScheme =")
        appendLine("    androidx.tv.material3.darkColorScheme(")
        for ((role, spec) in TV_ROLES) {
            appendLine("        $role = ${renderSpec(spec)},")
        }
        appendLine("    )")
    }

    private fun renderSpec(spec: String): String {
        val (property, alpha) = spec.split(":").let { it[0] to it.getOrNull(1) }
        return if (alpha == null) {
            "PutioDesignTokens.$property"
        } else {
            "PutioDesignTokens.$property.copy(alpha = ${alpha}f)"
        }
    }
}
