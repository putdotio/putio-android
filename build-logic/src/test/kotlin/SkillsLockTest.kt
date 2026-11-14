import java.io.File
import java.nio.file.Files
import org.gradle.api.GradleException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsLockTest {
    private val root: File = Files.createTempDirectory("skills-lock").toFile()
    private val tracked = mutableListOf<String>()

    @After
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theHashMatchesTheSkillsCli() {
        val files = mapOf(
            "SKILL.md" to "# Demo\n",
            "references/apis.md" to "apis\n",
            "references/Zeta.md" to "zeta\n",
            "references/éclair.md" to "eclair\n",
            "references/fig.md" to "fig\n",
            "references/日本.md" to "nihon\n",
            "scripts/run.py" to "print(1)\r\nprint(2)\r\n",
            "metadata.json" to "{}\n",
        )
        assertEquals(NON_ASCII_CLI_HASH, SkillsLock.folderHash(files.mapValues { it.value.toByteArray() }))
    }

    @Test
    fun untrackedFilesAreIgnored() {
        lockedSkill()
        root.resolve(".agents/skills/demo/scripts/__pycache__/run.cpython-313.pyc").apply { parentFile.mkdirs() }.writeText("pyc")
        root.resolve(".agents/skills/demo/.DS_Store").writeText("finder")
        root.resolve(".claude/skills/.DS_Store").writeText("finder")
        check()
    }

    @Test
    fun anOlderLockVersionFails() {
        lockedSkill()
        root.resolve("skills-lock.json").writeText(root.resolve("skills-lock.json").readText().replace("\"version\": 2", "\"version\": 1"))
        assertRejected("skills-lock.json is version 1; this check reads version 2")
    }

    @Test
    fun aMatchingSkillPasses() {
        lockedSkill()
        check()
    }

    @Test
    fun aTamperedHashFails() {
        lockedSkill(hash = CLI_HASH.replaceRange(0, 1, "0"))
        assertRejected("demo differs from its locked computedHash")
    }

    @Test
    fun anEditedSkillFails() {
        lockedSkill()
        root.resolve(".agents/skills/demo/SKILL.md").appendText("edit\n")
        assertRejected("demo differs from its locked computedHash")
    }

    @Test
    fun aLockedSkillMustExist() {
        lockedSkill()
        root.resolve(".agents/skills/demo").deleteRecursively()
        tracked.clear()
        assertRejected("demo is locked but .agents/skills/demo has no tracked files")
    }

    @Test
    fun everySkillNeedsItsLink() {
        lockedSkill()
        Files.delete(root.resolve(".claude/skills/demo").toPath())
        assertRejected(".claude/skills/demo is missing")
    }

    @Test
    fun aLinkMustPointAtItsSkill() {
        lockedSkill()
        skill("other", "SKILL.md" to "# Other\n")
        val link = root.resolve(".claude/skills/demo").toPath()
        Files.delete(link)
        Files.createSymbolicLink(link, File("../../.agents/skills/other").toPath())
        assertRejected(".claude/skills/demo links to ../../.agents/skills/other, not ../../.agents/skills/demo")
    }

    @Test
    fun aCopiedSkillIsNotALink() {
        lockedSkill()
        val link = root.resolve(".claude/skills/demo")
        Files.delete(link.toPath())
        link.resolve("SKILL.md").apply { parentFile.mkdirs() }.writeText("# Demo\n")
        assertRejected(".claude/skills/demo is not a link to ../../.agents/skills/demo")
    }

    @Test
    fun aDanglingLinkFails() {
        lockedSkill()
        Files.createSymbolicLink(root.resolve(".claude/skills/gone").toPath(), File("../../.agents/skills/gone").toPath())
        assertRejected(".claude/skills/gone links to a missing skill")
    }

    private fun lockedSkill(hash: String = CLI_HASH) {
        skill("demo", "SKILL.md" to "# Demo\n", "references/apis.md" to "apis\n", "references/Zeta.md" to "zeta\n")
        root.resolve("skills-lock.json").writeText(
            """{"version": 2, "skills": {"demo": {"source": "android/skills", "computedHash": "$hash"}}}""",
        )
    }

    private fun skill(name: String, vararg files: Pair<String, String>) {
        val dir = root.resolve(".agents/skills/$name")
        files.forEach { (path, text) ->
            dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
            tracked += ".agents/skills/$name/$path"
        }
        val links = root.resolve(".claude/skills").apply { mkdirs() }
        Files.createSymbolicLink(links.resolve(name).toPath(), File("../../.agents/skills/$name").toPath())
    }

    private fun check() {
        SkillsLock.check(root, tracked)
        SkillsLock.checkLinks(root)
    }

    private fun assertRejected(message: String) {
        val error = assertThrows(GradleException::class.java) { check() }
        assertTrue(error.message, error.message!!.contains("- $message\n"))
    }

    private companion object {
        const val CLI_HASH = "37e2137ae7c52476f9e4f038f24c909ccc8fca726955b1c7ba577a98fe803308"
        const val NON_ASCII_CLI_HASH = "8b1394f11b08b579b2295fa12d73ca3274a4c3810ea3f7173aa3ac9cca97f6fe"
    }
}
