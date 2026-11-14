import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Arrays
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.gradle.api.GradleException

object SkillsLock {
    const val LOCK_PATH = "skills-lock.json"
    const val SKILLS_PATH = ".agents/skills"
    const val LINKS_PATH = ".claude/skills"

    fun check(root: File, trackedFiles: List<String>) {
        fail(lockProblems(root, trackedFiles))
    }

    fun checkLinks(root: File) {
        fail(linkProblems(root))
    }

    fun folderHash(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.filterKeys { !isExcluded(it) }
            .map { (path, content) -> path.toByteArray() to normalizeLineEndings(content) }
            .sortedWith { a, b -> Arrays.compareUnsigned(a.first, b.first) }
            .forEach { (path, content) ->
                digest.update(path)
                digest.update(0)
                digest.update(content.size.toString().toByteArray())
                digest.update(0)
                digest.update(content)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(problems: List<String>) {
        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString("\n", prefix = "Skills do not match $LOCK_PATH:\n", postfix = "\n$FIX") { "- $it" },
            )
        }
    }

    private fun isExcluded(path: String): Boolean {
        val parts = path.split('/')
        return parts.any { it in EXCLUDED_FILES } || parts.dropLast(1).any { it in EXCLUDED_DIRS }
    }

    private fun normalizeLineEndings(content: ByteArray): ByteArray {
        if (content.contains(0)) return content
        val normalized = ByteArrayOutputStream(content.size)
        content.forEachIndexed { index, byte ->
            if (byte != CR || content.getOrNull(index + 1) != LF) normalized.write(byte.toInt())
        }
        return normalized.toByteArray()
    }

    private fun lockProblems(root: File, trackedFiles: List<String>): List<String> {
        val lock = runCatching { Json.parseToJsonElement(root.resolve(LOCK_PATH).readText()).jsonObject }
            .getOrElse { return listOf("$LOCK_PATH is not JSON: ${it.message}") }
        val version = (lock["version"] as? JsonPrimitive)?.intOrNull
        if (version != LOCK_VERSION) return listOf("$LOCK_PATH is version $version; this check reads version $LOCK_VERSION")
        val skills = lock["skills"] as? JsonObject ?: return listOf("$LOCK_PATH has no skills object")
        return skills.mapNotNull { (name, entry) ->
            val locked = ((entry as? JsonObject)?.get("computedHash") as? JsonPrimitive)?.content
            val prefix = "$SKILLS_PATH/$name/"
            val files = trackedFiles.filter { it.startsWith(prefix) }
                .mapNotNull { path -> root.resolve(path).takeIf(File::isFile)?.let { path.removePrefix(prefix) to it.readBytes() } }
                .toMap()
            when {
                locked == null -> "$name has no computedHash"
                files.isEmpty() -> "$name is locked but $SKILLS_PATH/$name has no tracked files"
                folderHash(files) != locked -> "$name differs from its locked computedHash"
                else -> null
            }
        }
    }

    private fun linkProblems(root: File): List<String> {
        val skills = root.resolve(SKILLS_PATH).listFiles(File::isDirectory).orEmpty().map { it.name }.toSet()
        val links = root.resolve(LINKS_PATH).listFiles().orEmpty().filter { it.name != FINDER_METADATA }.associateBy { it.name }
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

    private const val LOCK_VERSION = 2
    private const val CR: Byte = 13
    private const val LF: Byte = 10
    private const val FINDER_METADATA = ".DS_Store"
    private val EXCLUDED_FILES = setOf("metadata.json")
    private val EXCLUDED_DIRS = setOf(".git", "__pycache__", "__pypackages__")
    private const val FIX = "Run `npx skills update -p` for a pinned skill, or link a repo skill from $LINKS_PATH."
}
