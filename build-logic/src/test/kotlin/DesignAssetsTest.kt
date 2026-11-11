import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.GradleException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignAssetsTest {
    private val root: File = Files.createTempDirectory("design-assets").toFile()
    private val tokens = "{\"tokens\": true}\n".toByteArray()
    private val productionSource = "production art".toByteArray()
    private val iconSource = "icon art".toByteArray()
    private val devSource = "dev art".toByteArray()
    private val productionOutputs = channelOutputs("production")
    private val iconOutputs = channelOutputs("nightly")
    private val devOutputs = channelOutputs("dev")
    private val wordmark = DesignAssets.renderWordmark(WORDMARK_SVG, WORDMARK_ASSET, "3.3.0")
    private val monochrome = DesignAssets.renderMonochromeIcon(MONOCHROME_SVG, MONOCHROME_ASSET, "3.3.0")
    private val document: JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put(
            "package",
            buildJsonObject {
                put("name", "@putdotio/design")
                put("version", "3.3.0")
                put("tarball", "https://registry.npmjs.org/@putdotio/design/-/design-3.3.0.tgz")
                put("integrity", integrity("archive".toByteArray()))
            },
        )
        put(
            "tokens",
            buildJsonObject {
                put("asset", "dist/tokens.dtcg.json")
                put("sourceSha256", DesignAssets.sha256(tokens))
            },
        )
        put("productionIcon", iconLock("system/assets/app-icon-prod-dark.png", productionSource, productionOutputs))
        put("nightlyIcon", iconLock("system/assets/app-icon-nightly-stars.png", iconSource, iconOutputs))
        put("devIcon", iconLock("system/assets/app-icon-dev-blueprint.png", devSource, devOutputs))
        put("wordmark", vectorLock(WORDMARK_ASSET, WORDMARK_SVG, wordmark))
        put("monochromeIcon", vectorLock(MONOCHROME_ASSET, MONOCHROME_SVG, monochrome))
    }

    init {
        write(DesignAssets.TOKENS_PATH, tokens)
        renderedIcons().forEach(::write)
        write(DesignAssets.WORDMARK_PATH, wordmark)
        write(DesignAssets.MONOCHROME_ICON_PATH, monochrome)
    }

    @After
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theRepositoryLockParses() {
        val lock = DesignAssets.parseLock(File("../${DesignAssets.LOCK_PATH}").readText())
        assertEquals("@putdotio/design", lock.pkg.name)
        assertEquals(DesignAssets.OUTPUT_PATHS.toSet(), DesignAssets.lockedOutputs(lock).map { it.first }.toSet())
    }

    @Test
    fun aValidLockPassesTheOfflineCheck() {
        check(lock())
    }

    @Test
    fun aDuplicateKeyIsRejected() {
        val json = document.toString().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
        assertFails("duplicate key 'schemaVersion'") { check(json) }
    }

    @Test
    fun theTarballMustMatchTheVersion() {
        val json = edit("package") { it + ("version" to JsonPrimitive("3.4.0")) }
        assertFails("tarball does not match the locked version") { check(json) }
    }

    @Test
    fun everyDensityMustBeLocked() {
        val json = editOutputs("nightlyIcon", "outputs") { it.dropLast(1) }
        assertFails("each launcher density exactly once") { check(json) }
    }

    @Test
    fun driftedOutputsAreRejected() {
        listOf(
            DesignAssets.TOKENS_PATH,
            iconPath("xhdpi"),
            iconPath("xxhdpi", "tv/src/nightly/res"),
            iconPath("hdpi", "core/design/src/main/res"),
            DesignAssets.WORDMARK_PATH,
        ).forEach(::assertDriftDetected)
    }

    @Test
    fun missingLauncherIconsAreRejected() {
        root.resolve(iconPath("mdpi")).delete()
        root.resolve(iconPath("xxxhdpi", "core/design/src/main/res")).delete()

        val error = assertFails("missing mobile/src/nightly/res/drawable-mdpi/putio_icon.png") { check(lock()) }
        assertTrue(error.message, "missing core/design/src/main/res/drawable-xxxhdpi/putio_icon.png" in error.message!!)
    }

    @Test
    fun artOutputsMustPairEachDensityWithItsArtSize() {
        val json = editOutputs("devIcon", "artOutputs") { outputs ->
            listOf(JsonObject(outputs.first().jsonObject + ("size" to JsonPrimitive(48)))) + outputs.drop(1)
        }
        assertFails("devIcon.artOutputs[0] must pair a launcher density") { check(json) }
    }

    @Test
    fun driftedArtAndDevIconsAreRejected() {
        listOf(
            "mobile/src/main/res/drawable-xxhdpi/putio_launcher_art.png",
            "mobile/src/debug/res/drawable-mdpi/putio_icon.png",
            "mobile/src/debug/res/drawable-xxxhdpi/putio_launcher_art.png",
        ).forEach(::assertDriftDetected)
    }

    @Test
    fun theProductionIconMustLockEveryDensity() {
        val json = editOutputs("productionIcon", "outputs") { it.dropLast(1) }
        assertFails("productionIcon.outputs must list each launcher density") { check(json) }
    }

    @Test
    fun theWordmarkKeepsFillsAndViewBoxOffset() {
        val drawable = wordmark.toString(Charsets.UTF_8)
        assertTrue(drawable, "android:viewportHeight=\"20\"" in drawable)
        assertTrue(drawable, "android:translateY=\"4\"" in drawable)
        assertTrue(drawable, "android:fillColor=\"#FFFFFFFF\"\n            android:fillType=\"evenOdd\"" in drawable)
        assertTrue(drawable, "android:fillColor=\"#FFFDCE45\"\n            android:pathData=\"M20 0H30V10H20Z\"" in drawable)
        assertEquals(1, Regex("fillType").findAll(drawable).count())
        assertFalse(drawable, "clip" in drawable)
    }

    @Test
    fun theWordmarkRejectsShapesItCannotModel() {
        val source = WORDMARK_SVG.toString(Charsets.UTF_8).replace("<path d=", "<path transform=\"scale(2)\" d=")
        assertFails("path the converter cannot model") {
            DesignAssets.renderWordmark(source.toByteArray(), WORDMARK_ASSET, "3.3.0")
        }
    }

    @Test
    fun theMonochromeMarkFillsUnfilledPathsOnTheAdaptiveGrid() {
        val drawable = monochrome.toString(Charsets.UTF_8)
        assertTrue(drawable, "android:width=\"108dp\"" in drawable)
        assertTrue(drawable, "android:viewportWidth=\"108\"" in drawable)
        assertTrue(drawable, "android:fillColor=\"#FFFFFFFF\"\n            android:fillType=\"evenOdd\"" in drawable)
        val unfilledWordmark = WORDMARK_SVG.toString(Charsets.UTF_8).replace(" fill=\"#FDCE45\"", "")
        assertFails("path the converter cannot model") {
            DesignAssets.renderWordmark(unfilledWordmark.toByteArray(), WORDMARK_ASSET, "3.3.0")
        }
    }

    @Test
    fun aLauncherIconIsDownscaledToEachLockedSize() {
        val art = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                color = Color(0x12, 0x34, 0x56)
                fillRect(0, 0, 1024, 1024)
                dispose()
            }
        }
        val source = ByteArrayOutputStream().also { ImageIO.write(art, "png", it) }.toByteArray()
        val icon = DesignAssets.parseLock(lock()).productionIcon

        val rendered = DesignAssets.renderLauncherIcon(source, icon)

        assertEquals(icon.allOutputs.flatMap { it.paths }.toSet(), rendered.keys)
        icon.allOutputs.forEach { output ->
            val image = ImageIO.read(ByteArrayInputStream(rendered.getValue(output.paths.single())))
            assertEquals(output.size, image.width)
            assertEquals(output.size, image.height)
            assertEquals(0xFF123456.toInt(), image.getRGB(output.size / 2, output.size / 2))
        }
    }

    @Test
    fun syncRejectsUnlockedAssetsWithoutWriting() {
        val rendered = renderedIcons() + ("mobile/src/nightly/res/drawable-hdpi/putio_icon.png" to "resampled".toByteArray())
        val paths = replaceOutputs("previous".toByteArray())

        var error = assertFails("unlocked design assets; nothing written") {
            sync(lockArchive(tokens = "tampered".toByteArray()), rendered)
        }
        assertTrue(error.message, "dist/tokens.dtcg.json is ${DesignAssets.sha256("tampered".toByteArray())}" in error.message!!)
        assertTrue(error.message, "drawable-hdpi/putio_icon.png is" in error.message!!)
        assertFalse(error.message, "app-icon-nightly-stars.png is" in error.message!!)
        assertUnchanged(paths)

        error = assertFails("unlocked design assets; nothing written") {
            sync(lockArchive(iconSource = "tampered art".toByteArray()), renderedIcons())
        }
        assertTrue(
            error.message,
            "app-icon-nightly-stars.png is ${DesignAssets.sha256("tampered art".toByteArray())}" in error.message!!,
        )
        assertUnchanged(paths)
    }

    @Test
    fun syncWritesEveryLockedOutput() {
        val archive = lockArchive()
        replaceOutputs("previous".toByteArray())

        sync(archive, renderedIcons())

        check(root.resolve(DesignAssets.LOCK_PATH).readText())
    }

    @Test
    fun syncRejectsAPackageWithoutTheLockedIntegrity() {
        val lock = DesignAssets.parseLock(lock())

        assertFails("does not match locked sha512 SRI") {
            DesignAssets.sync(lock, root, "other archive".toByteArray(), unpack = { archiveFiles() }, log = {})
        }
        assertTrue(root.resolve(DesignAssets.TOKENS_PATH).readBytes().contentEquals(tokens))
    }

    @Test
    fun syncRejectsAPackageMissingALockedAsset() {
        val lock = DesignAssets.parseLock(lock())

        assertFails("locked package is missing system/assets/app-icon-dev-blueprint.png") {
            DesignAssets.sync(
                lock, root, "archive".toByteArray(),
                unpack = { archiveFiles() - "system/assets/app-icon-dev-blueprint.png" },
                log = {},
            )
        }
    }

    private class Archive(val bytes: ByteArray, val lock: String, val files: Map<String, ByteArray>)

    private fun lockArchive(tokens: ByteArray? = null, iconSource: ByteArray? = null): Archive {
        val files = archiveFiles(tokens, iconSource)
        val bytes = files.entries.joinToString("\n") { (name, data) -> "$name ${DesignAssets.sha256(data)}" }.toByteArray()
        val json = JsonObject(
            document + ("package" to JsonObject(document.getValue("package").jsonObject + ("integrity" to JsonPrimitive(integrity(bytes))))),
        ).toString()
        root.resolve(DesignAssets.LOCK_PATH).apply { parentFile.mkdirs() }.writeText(json)
        return Archive(bytes, json, files)
    }

    private fun archiveFiles(tokens: ByteArray? = null, iconSource: ByteArray? = null): Map<String, ByteArray> = mapOf(
        "dist/tokens.dtcg.json" to (tokens ?: this.tokens),
        "system/assets/app-icon-prod-dark.png" to productionSource,
        "system/assets/app-icon-nightly-stars.png" to (iconSource ?: this.iconSource),
        "system/assets/app-icon-dev-blueprint.png" to devSource,
        WORDMARK_ASSET to WORDMARK_SVG,
        MONOCHROME_ASSET to MONOCHROME_SVG,
    )

    private fun sync(archive: Archive, rendered: Map<String, ByteArray>) {
        DesignAssets.sync(
            DesignAssets.parseLock(archive.lock),
            root,
            archive.bytes,
            unpack = { archive.files },
            log = {},
            renderLauncher = { _, icon -> icon.allOutputs.flatMap { it.paths }.associateWith(rendered::getValue) },
        )
    }

    private fun renderedIcons(): Map<String, ByteArray> =
        listOf(
            Triple(DesignAssets.PRODUCTION_RES_DIRS, DesignAssets.PRODUCTION_ART_RES_DIRS, productionOutputs),
            Triple(DesignAssets.NIGHTLY_RES_DIRS, DesignAssets.NIGHTLY_ART_RES_DIRS, iconOutputs),
            Triple(DesignAssets.DEV_RES_DIRS, DesignAssets.DEV_ART_RES_DIRS, devOutputs),
        ).flatMap { (resDirs, artResDirs, outputs) ->
            listOf(
                Triple("outputs", resDirs, DesignAssets.LAUNCHER_ICON_FILE),
                Triple("artOutputs", artResDirs, DesignAssets.LAUNCHER_ART_FILE),
            ).flatMap { (kind, dirs, fileName) ->
                dirs.flatMap { res -> outputs.getValue(kind).map { (density, data) -> "$res/drawable-$density/$fileName" to data } }
            }
        }.toMap()

    private fun replaceOutputs(data: ByteArray): List<String> {
        val paths = listOf(DesignAssets.TOKENS_PATH, DesignAssets.WORDMARK_PATH, DesignAssets.MONOCHROME_ICON_PATH) +
            renderedIcons().keys
        paths.forEach { write(it, data) }
        return paths
    }

    private fun assertUnchanged(paths: List<String>) {
        paths.forEach { assertEquals(it, "previous", root.resolve(it).readText()) }
    }

    private fun assertDriftDetected(path: String) {
        val file = root.resolve(path)
        val original = file.readBytes()
        file.writeText("edited")
        assertFails("drifted $path") { check(lock()) }
        file.writeBytes(original)
    }

    private fun check(json: String) {
        DesignAssets.check(DesignAssets.parseLock(json), DesignAssets.filesByPath(root, root.walk().toList()))
    }

    private fun assertFails(expected: String, block: () -> Unit): GradleException {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("$expected: ${error?.message}", error is GradleException && expected in error.message!!)
        return error as GradleException
    }

    private fun lock(): String = document.toString()

    private fun edit(field: String, change: (JsonObject) -> Map<String, JsonElement>): String =
        JsonObject(document + (field to JsonObject(change(document.getValue(field).jsonObject)))).toString()

    private fun editOutputs(icon: String, kind: String, change: (List<JsonElement>) -> List<JsonElement>): String =
        edit(icon) { it + (kind to JsonArray(change(it.getValue(kind) as JsonArray))) }

    private fun write(path: String, data: ByteArray) {
        root.resolve(path).apply { parentFile.mkdirs() }.writeBytes(data)
    }

    private fun iconPath(density: String, res: String = "mobile/src/nightly/res"): String =
        "$res/drawable-$density/putio_icon.png"

    private val JsonElement.jsonObject: JsonObject get() = this as JsonObject

    private companion object {
        const val WORDMARK_ASSET = "system/assets/logo-retro-dark.svg"
        val WORDMARK_SVG = (
            "<svg width=\"40\" height=\"20\" viewBox=\"0 -4 40 20\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">" +
                "<path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M0 0H10V10H0Z\" fill=\"white\"></path>" +
                "<path d=\"M20 0H30V10H20Z\" fill=\"#FDCE45\"></path>" +
                "</svg>"
            ).toByteArray()
        const val MONOCHROME_ASSET = "system/assets/app-icon-monochrome.svg"
        val MONOCHROME_SVG = (
            "<!-- comment -->" +
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\">" +
                "<path fill-rule=\"evenodd\" d=\"M30 30H78V78H30ZM40 40V68H68V40Z\"/>" +
                "</svg>"
            ).toByteArray()

        fun channelOutputs(channel: String): Map<String, Map<String, ByteArray>> = mapOf(
            "outputs" to DesignAssets.LAUNCHER_ICON_DENSITIES,
            "artOutputs" to DesignAssets.LAUNCHER_ART_DENSITIES,
        ).mapValues { (kind, densities) -> densities.keys.associateWith { "$it $channel $kind".toByteArray() } }

        fun iconLock(asset: String, source: ByteArray, outputs: Map<String, Map<String, ByteArray>>): JsonObject =
            buildJsonObject {
                put("asset", asset)
                put("sourceSha256", DesignAssets.sha256(source))
                mapOf(
                    "outputs" to DesignAssets.LAUNCHER_ICON_DENSITIES,
                    "artOutputs" to DesignAssets.LAUNCHER_ART_DENSITIES,
                ).forEach { (kind, densities) ->
                    put(
                        kind,
                        buildJsonArray {
                            densities.forEach { (density, size) ->
                                add(
                                    buildJsonObject {
                                        put("density", density)
                                        put("size", size)
                                        put("sha256", DesignAssets.sha256(outputs.getValue(kind).getValue(density)))
                                    },
                                )
                            }
                        },
                    )
                }
            }

        fun vectorLock(asset: String, source: ByteArray, output: ByteArray): JsonObject = buildJsonObject {
            put("asset", asset)
            put("sourceSha256", DesignAssets.sha256(source))
            put("outputSha256", DesignAssets.sha256(output))
        }

        fun integrity(bytes: ByteArray): String =
            "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))
    }
}
