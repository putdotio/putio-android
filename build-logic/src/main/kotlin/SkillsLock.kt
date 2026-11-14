import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.gradle.api.GradleException

object SkillsLock {
    const val LOCK_PATH = "skills-lock.json"
    const val SKILLS_PATH = ".agents/skills"
    const val LINKS_PATH = ".claude/skills"

    fun check(root: File) {
        val problems = lockProblems(root) + linkProblems(root)
        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString("\n", prefix = "Skills do not match $LOCK_PATH:\n", postfix = "\n$FIX") { "- $it" },
            )
        }
    }

    fun folderHash(dir: File): String {
        val files = dir.walkTopDown()
            .onEnter { it == dir || (it.name !in SKIPPED_DIRS && !Files.isSymbolicLink(it.toPath())) }
            .filter { it.name !in SKIPPED_FILES && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .sortedWith { a, b -> localeCompare(a.first, b.first) }
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (path, file) ->
            digest.update(path.toByteArray())
            digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun localeCompare(a: String, b: String): Int {
        a.zip(b).forEach { (x, y) -> primary(x).compareTo(primary(y)).takeIf { it != 0 }?.let { return it } }
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.isUpperCase().compareTo(y.isUpperCase()) } ?: 0
    }

    private fun primary(char: Char): Int =
        ROOT_ORDER.indexOf(char.lowercaseChar()).takeIf { it >= 0 } ?: (ROOT_ORDER.length + char.code)

    private fun lockProblems(root: File): List<String> {
        val skills = runCatching {
            Json.parseToJsonElement(root.resolve(LOCK_PATH).readText()).jsonObject["skills"] as JsonObject
        }.getOrElse { return listOf("$LOCK_PATH has no skills object: ${it.message}") }
        return skills.mapNotNull { (name, entry) ->
            val locked = ((entry as? JsonObject)?.get("computedHash") as? JsonPrimitive)?.content
            val dir = root.resolve("$SKILLS_PATH/$name")
            when {
                locked == null -> "$name has no computedHash"
                !dir.isDirectory -> "$name is locked but $SKILLS_PATH/$name is missing"
                folderHash(dir) != locked -> "$name differs from its locked computedHash"
                else -> null
            }
        }
    }

    private fun linkProblems(root: File): List<String> {
        val skills = root.resolve(SKILLS_PATH).listFiles(File::isDirectory).orEmpty().map { it.name }.toSet()
        val links = root.resolve(LINKS_PATH).listFiles().orEmpty().filter { it.name !in SKIPPED_FILES }.associateBy { it.name }
        val missing = (skills - links.keys).sorted().map { "$LINKS_PATH/$it is missing" }
        val wrong = links.toSortedMap().mapNotNull { (name, link) ->
            val expected = "../../$SKILLS_PATH/$name"
            val target = if (Files.isSymbolicLink(link.toPath())) Files.readSymbolicLink(link.toPath()).toString() else null
            when {
                target == null -> "$LINKS_PATH/$name is not a link to $expected"
                target.replace('\\', '/') != expected -> "$LINKS_PATH/$name links to $target, not $expected"
                name !in skills -> "$LINKS_PATH/$name links to a missing skill"
                else -> null
            }
        }
        return missing + wrong
    }

    private val SKIPPED_DIRS = setOf(".git", "node_modules")
    private val SKIPPED_FILES = setOf(".DS_Store")
    private const val ROOT_ORDER =
        "\t\n\u000b\u000c\r _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$0123456789abcdefghijklmnopqrstuvwxyz"
    private const val FIX = "Run `npx skills update -p` for a pinned skill, or link a repo skill from $LINKS_PATH."
}
