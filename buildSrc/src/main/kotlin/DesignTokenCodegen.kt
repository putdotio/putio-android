import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates the put.io Compose color schemes from the vendored DTCG token
 * graph (`design/tokens.dtcg.json`, from @putdotio/design). The M3-role map
 * below is the tier-2 binding contract from putio-design
 * `platforms/android/DESIGN.md`; the app never hand-writes a color.
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
    )

    /**
     * M3 role → token property (optionally `property:alpha`). These are the
     * contracted roles from DESIGN.md. The container and background entries
     * keep stock components from falling back to M3 baseline purple:
     * background/onBackground mirror surface/onSurface;
     * primaryContainer/onPrimaryContainer carry the FAB spec from the
     * android-s00-shell card (--yellow-solid / --primary-foreground);
     * secondary/secondaryContainer/onSecondaryContainer carry the card's nav
     * label and indicator; inverse roles carry the snackbar contract; and
     * surfaceContainerLow carries the stock bottom-sheet surface.
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

    private data class RawToken(val path: String, val value: String, val cssName: String?, val mode: String?)

    fun generate(dtcgJson: String, designVersion: String): String {
        val tokens = parse(dtcgJson)
        val resolved = TOKEN_BINDINGS.associate { (property, cssName) ->
            property to resolve(tokens, cssName)
        }
        return render(resolved, designVersion)
    }

    private fun parse(dtcgJson: String): Map<String, RawToken> {
        val root = Json.parseToJsonElement(dtcgJson).jsonObject
        val out = mutableMapOf<String, RawToken>()
        fun walk(obj: JsonObject, path: String) {
            for ((key, element) in obj) {
                if (key.startsWith("$")) continue
                val child = element as? JsonObject ?: continue
                val value = child["\$value"]
                if (value is JsonPrimitive) {
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    val putio = child["\$extensions"]?.jsonObject?.get("putio")?.jsonObject
                    out[childPath] = RawToken(
                        path = childPath,
                        value = value.content,
                        cssName = putio?.get("cssName")?.jsonPrimitive?.content,
                        mode = putio?.get("mode")?.jsonPrimitive?.content,
                    )
                } else {
                    walk(child, if (path.isEmpty()) key else "$path.$key")
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

    private fun render(resolved: Map<String, Long>, designVersion: String): String = buildString {
        appendLine("// Generated by :app:generateDesignTokens from design/tokens.dtcg.json")
        appendLine("// (@putdotio/design $designVersion). Do not edit; see design/README.md.")
        appendLine("package io.putdotio.android.design")
        appendLine()
        appendLine("import androidx.compose.ui.graphics.Color")
        appendLine()
        appendLine("/** Dark-mode put.io tokens. Property names mirror the token graph cssNames. */")
        appendLine("object PutioDesignTokens {")
        for ((property, cssName) in TOKEN_BINDINGS) {
            val argb = resolved.getValue(property)
            appendLine("    /** --$cssName */")
            appendLine("    val $property: Color = Color(0x${"%08X".format(argb)})")
        }
        appendLine("}")
        appendLine()
        appendLine("/** The tier-2 binding: one dark scheme, every component stock Material 3. */")
        appendLine("fun putioDarkColorScheme(): androidx.compose.material3.ColorScheme =")
        appendLine("    androidx.compose.material3.darkColorScheme(")
        for ((role, spec) in M3_ROLES) {
            appendLine("        $role = ${renderSpec(spec)},")
        }
        appendLine("    )")
        appendLine()
        appendLine("/** Same contract projected onto the Compose for TV color scheme. */")
        appendLine("fun putioTvDarkColorScheme(): androidx.tv.material3.ColorScheme =")
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
