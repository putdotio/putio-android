import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `design/brand-fonts.lock.json`: the licensed GT America and Berkeley Mono files the apps embed.
 * `scripts/sync-brand-fonts.sh` fetches them into the lock's git-ignored directory; the build never
 * downloads them. A build without them sets DESIGN.md's families in the system face.
 */
object BrandFonts {
    const val BASE_URL: String = "https://static.put.io"

    /** Android resource names, so a file keeps its name in `res/font`. */
    private val FILE_NAME = Regex("""(gt_america|berkeley_mono)_[a-z0-9_]*[a-z0-9]\.(ttf|otf)""")
    private val SOURCE_PATH = Regex("""fonts/[a-z0-9-]+(/[a-z0-9-]+)*/[a-z0-9-]+\.(ttf|otf)""")
    private val DIRECTORY = Regex("""[a-z0-9_-]+(/[a-z0-9_-]+)*""")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val FACE_KEYS = setOf("path", "sha256", "family", "weight", "weightRange")

    /** A locked file: a static face at [weight], or a variable one whose weight axis spans [weightRange]. */
    data class Face(
        val file: String,
        val family: String,
        val sha256: String,
        val weight: Int? = null,
        val weightRange: IntRange? = null,
    ) {
        fun covers(requested: Int): Boolean = weight == requested || weightRange?.contains(requested) == true
    }

    data class Lock(val directory: String, val faces: List<Face>)

    fun parseLock(json: String): Lock {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: error("brand font lock must be a JSON object")
        check(root.keys == setOf("baseUrl", "directory", "files")) { "brand font lock fields do not match the schema" }
        check(root.string("baseUrl") == BASE_URL) { "brand font lock must fetch from $BASE_URL" }
        val directory = root.string("directory")
        check(DIRECTORY.matches(directory)) { "brand font lock directory must be a plain relative path: '$directory'" }
        val files = root["files"] as? JsonObject ?: error("brand font lock files must be an object")
        check(files.isNotEmpty()) { "brand font lock has no files" }
        val faces = files.map { (name, value) ->
            check(FILE_NAME.matches(name)) { "brand font file name must be an Android font resource name: '$name'" }
            val entry = value as? JsonObject ?: error("$name must be an object")
            check(entry.keys - FACE_KEYS == emptySet<String>()) { "$name has unsupported keys ${entry.keys - FACE_KEYS}" }
            val path = entry.string("path")
            check(SOURCE_PATH.matches(path)) { "$name path must stay under $BASE_URL/fonts: '$path'" }
            check(path.substringAfterLast('.') == name.substringAfterLast('.')) { "$name and its path differ in format" }
            val sha256 = entry.string("sha256")
            check(SHA256.matches(sha256)) { "$name sha256 must be a lowercase SHA-256 digest" }
            val weight = entry["weight"]?.let { (it as? JsonPrimitive)?.intOrNull ?: error("$name weight must be an integer") }
            val range = entry["weightRange"]?.let { element ->
                val bounds = (element as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull }
                val (low, high) = bounds?.takeIf { it.size == 2 && it.all { b -> b != null } }?.map { it!! }
                    ?: error("$name weightRange must be [min, max]")
                check(low < high) { "$name weightRange must rise" }
                low..high
            }
            check((weight == null) != (range == null)) { "$name needs exactly one of weight and weightRange" }
            listOfNotNull(weight, range?.first, range?.last).forEach {
                check(it in 1..1000) { "$name weights must be 1..1000" }
            }
            Face(name, entry.string("family"), sha256, weight, range)
        }
        return Lock(directory, faces)
    }

    /**
     * The locked files present among [files], matched by name, after checking each one's digest. A
     * present file that does not match its digest fails; so does a missing one when [required].
     */
    fun installed(lock: Lock, files: Iterable<File>, required: Boolean): Set<String> {
        val byName = files.filter(File::isFile).associateBy(File::getName)
        val mismatched = lock.faces.filter { face ->
            byName[face.file]?.let { sha256(it.readBytes()) != face.sha256 } == true
        }
        check(mismatched.isEmpty()) {
            "brand fonts do not match design/brand-fonts.lock.json: ${mismatched.joinToString { it.file }}; " +
                "run ./scripts/sync-brand-fonts.sh"
        }
        val installed = lock.faces.map(Face::file).filter(byName::containsKey).toSet()
        if (required) {
            val missing = lock.faces.map(Face::file) - installed
            check(missing.isEmpty()) {
                "release builds embed the brand fonts, but ${lock.directory} lacks ${missing.joinToString()}; " +
                    "run ./scripts/sync-brand-fonts.sh"
            }
        }
        return installed
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: error("brand font lock needs a non-empty string $key")
}
