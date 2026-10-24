import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandFontsTest {
    @Test
    fun theRepositoryLockParses() {
        val lock = BrandFonts.parseLock(File("../design/brand-fonts.lock.json").readText())
        assertEquals("core/design/brand-fonts", lock.directory)
        assertEquals(setOf("GT America", "Berkeley Mono"), lock.faces.map { it.family }.toSet())
    }

    @Test
    fun lockRejectsWhatCouldEscapeOrMislabelAFace() {
        listOf(
            lock(baseUrl = "https://example.com") to "fetch from",
            lock(directory = "../fonts") to "directory",
            lock(name = "GT-America.ttf") to "resource name",
            lock(path = "fonts/../secrets/a.ttf") to "path",
            lock(path = "fonts/gt/a.otf") to "format",
            lock(weight = "\"weightRange\": [900, 100]") to "rise",
            lock(weight = "\"weight\": 400, \"weightRange\": [100, 900]") to "exactly one",
        ).forEach { (json, expected) ->
            val error = runCatching { BrandFonts.parseLock(json) }.exceptionOrNull()
            assertTrue("$expected: ${error?.message}", error is IllegalStateException && error.message!!.contains(expected))
        }
    }

    @Test
    fun installedFontsMayBeAbsentOrPartialButNeverCorrupt() {
        val dir = Files.createTempDirectory("brand-fonts").toFile()
        try {
            val font = "font".toByteArray()
            val face = { name: String -> BrandFonts.Face(name, "GT America", sha256(font), weight = 400) }
            val lock = BrandFonts.Lock("fonts", listOf(face("gt_america_a.ttf"), face("gt_america_b.ttf")))
            assertEquals(emptySet<String>(), BrandFonts.installed(lock, dir.listFiles()!!.toList(), required = false))
            dir.resolve("gt_america_a.ttf").writeBytes(font)
            dir.resolve("unrelated.ttf").writeBytes("x".toByteArray())
            assertEquals(setOf("gt_america_a.ttf"), BrandFonts.installed(lock, dir.listFiles()!!.toList(), required = false))
            val missing = runCatching { BrandFonts.installed(lock, dir.listFiles()!!.toList(), required = true) }
            assertTrue(missing.exceptionOrNull()!!.message!!.contains("gt_america_b.ttf"))
            dir.resolve("gt_america_b.ttf").writeBytes("corrupt".toByteArray())
            val corrupt = runCatching { BrandFonts.installed(lock, dir.listFiles()!!.toList(), required = false) }
            assertTrue(corrupt.exceptionOrNull()!!.message!!.contains("do not match"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun lock(
        baseUrl: String = "https://static.put.io",
        directory: String = "core/design/brand-fonts",
        name: String = "gt_america_regular.ttf",
        path: String = "fonts/gt-america/regular.ttf",
        weight: String = "\"weight\": 400",
    ) = """
        {"baseUrl": "$baseUrl", "directory": "$directory", "files": {"$name": {
          "path": "$path", "sha256": "${"0".repeat(64)}", "family": "GT America", $weight}}}
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
