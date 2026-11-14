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

    @After
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theRepositorySkillsMatchTheirLock() {
        SkillsLock.check(File(".."))
    }

    @Test
    fun theHashMatchesTheSkillsCli() {
        skill("demo", "SKILL.md" to "# Demo\n", "references/apis.md" to "apis\n", "references/Zeta.md" to "zeta\n")
        assertEquals(CLI_HASH, SkillsLock.folderHash(root.resolve(".agents/skills/demo")))
    }

    @Test
    fun aMatchingSkillPasses() {
        lockedSkill()
        SkillsLock.check(root)
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
        Files.delete(root.resolve(".claude/skills/demo").toPath())
        assertRejected("demo is locked but .agents/skills/demo is missing")
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
            """{"version": 1, "skills": {"demo": {"source": "android/skills", "computedHash": "$hash"}}}""",
        )
    }

    private fun skill(name: String, vararg files: Pair<String, String>) {
        val dir = root.resolve(".agents/skills/$name")
        files.forEach { (path, text) -> dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        val links = root.resolve(".claude/skills").apply { mkdirs() }
        Files.createSymbolicLink(links.resolve(name).toPath(), File("../../.agents/skills/$name").toPath())
    }

    private fun assertRejected(message: String) {
        val error = assertThrows(GradleException::class.java) { SkillsLock.check(root) }
        assertTrue(error.message, error.message!!.contains("- $message\n"))
    }

    private companion object {
        const val CLI_HASH = "3845c0d946aa12bdc415863d0ce3ed054051817c560263a1dcee49e58efbae4c"
    }
}
