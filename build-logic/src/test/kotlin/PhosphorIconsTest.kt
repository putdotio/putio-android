import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import org.gradle.api.GradleException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhosphorIconsTest {
    private val root: File = Files.createTempDirectory("phosphor-icons").toFile()
    private val output = "locked drawable\n".toByteArray()
    private val outputFile = root.resolve("core/design/src/main/res/drawable/ic_ph_folder.xml").apply {
        parentFile.mkdirs()
        writeBytes(output)
    }

    @After
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theRepositoryLockParses() {
        val lock = PhosphorIcons.parseLock(File("../design/phosphor-icons.lock.json").readText())
        assertEquals("@phosphor-icons/core", lock.pkg.name)
        assertTrue(lock.icons.isNotEmpty())
    }

    @Test
    fun aValidLockPassesTheOfflineCheck() {
        PhosphorIcons.check(PhosphorIcons.parseLock(lock()), drawables())
    }

    @Test
    fun aDuplicateKeyIsRejected() {
        assertRejected(lock().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"), "duplicate key 'schemaVersion'")
    }

    @Test
    fun nonIntegerSchemaVersionsAreRejected() {
        listOf("true", "1.0").forEach { assertRejected(lock(schemaVersion = it), "lock must use schemaVersion 1") }
    }

    @Test
    fun invalidIntegrityValuesAreRejected() {
        assertRejected(lock(integrity = "sha512-not-base64"), "must contain valid base64")
        assertRejected(lock(integrity = "sha512-YQ=="), "must contain a canonical SHA-512 digest")
    }

    @Test
    fun aTintMustBeAGeneratedTokenColour() {
        listOf("#FFFDCE45", "@color/black", "@android:color/white").forEach {
            assertRejected(lock(tint = it), "tint must be a generated @color/putio_* token")
        }
    }

    @Test
    fun aTintedIconKeepsBlackPathsUnderItsTokenTint() {
        val icon = PhosphorIcons.Icon(
            "mobile/src/main/res", "ic_ph_shortcut_plus", "assets/regular/plus.svg", "0".repeat(64), "0".repeat(64),
            "@color/putio_yellow_solid",
        )
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><path d="M0 0H8V8H0Z"/></svg>"""

        val drawable = PhosphorIcons.renderDrawable(icon, "2.1.1", svg.toByteArray()).toString(Charsets.UTF_8)

        assertTrue(drawable, "<!-- Phosphor plus v2.1.1 (MIT)." in drawable)
        assertTrue(drawable, "android:viewportHeight=\"256\"\n    android:tint=\"@color/putio_yellow_solid\">" in drawable)
        assertTrue(drawable, "android:fillColor=\"#FF000000\"" in drawable)
    }

    @Test
    fun aMissingOutputIsRejected() {
        val lock = PhosphorIcons.parseLock(lock())
        outputFile.delete()

        assertCheckFails(lock, "missing core/design/src/main/res/drawable/ic_ph_folder.xml")
    }

    @Test
    fun aDriftedOutputIsRejected() {
        val lock = PhosphorIcons.parseLock(lock())
        outputFile.writeText("drifted\n")

        assertCheckFails(lock, "drifted core/design/src/main/res/drawable/ic_ph_folder.xml")
    }

    @Test
    fun aStaleGeneratedOutputIsRejected() {
        val lock = PhosphorIcons.parseLock(lock())
        outputFile.resolveSibling("ic_ph_stale.xml").writeText(PhosphorIcons.GENERATED_MARKER)

        assertCheckFails(lock, "stale core/design/src/main/res/drawable/ic_ph_stale.xml")
    }

    private fun drawables(): Map<String, File> =
        PhosphorIcons.drawablesByPath(root, root.walk().filter { it.name.startsWith("ic_ph_") }.toList())

    private fun assertCheckFails(lock: PhosphorIcons.Lock, expected: String) {
        val error = runCatching { PhosphorIcons.check(lock, drawables()) }.exceptionOrNull()
        assertTrue("$expected: ${error?.message}", error is GradleException && expected in error.message!!)
    }

    private fun assertRejected(json: String, expected: String) {
        val error = runCatching { PhosphorIcons.parseLock(json) }.exceptionOrNull()
        assertTrue("$expected: ${error?.message}", error is GradleException && expected in error.message!!)
    }

    private fun lock(
        schemaVersion: String = "1",
        integrity: String = "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", "archive".toByteArray())),
        tint: String? = null,
    ): String = """
        {
          "schemaVersion": $schemaVersion,
          "package": {
            "name": "@phosphor-icons/core",
            "version": "2.1.1",
            "tarball": "https://registry.npmjs.org/@phosphor-icons/core/-/core-2.1.1.tgz",
            "integrity": "$integrity"
          },
          "icons": [
            {
              "resDir": "core/design/src/main/res",
              "resource": "ic_ph_folder",
              "asset": "assets/regular/folder.svg",
              "sourceSha256": "${PhosphorIcons.sha256("source".toByteArray())}",
              "outputSha256": "${PhosphorIcons.sha256(output)}"${tint?.let { ",\n              \"tint\": \"$it\"" }.orEmpty()}
            }
          ]
        }
    """.trimIndent()

    private fun digest(algorithm: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(bytes)
}
