import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "it downloads from static.put.io and verifies every file on each run")
abstract class SyncBrandFontsTask : DefaultTask() {

    @get:Internal
    abstract val brandFontsLock: RegularFileProperty

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun sync() {
        val lock = BrandFonts.parseLock(brandFontsLock.get().asFile.readText())
        BrandFontSync.sync(lock, rootDirectory.get().asFile, log = { logger.lifecycle(it) })
    }
}

object BrandFontSync {
    const val ATTEMPTS: Int = 3
    private val TIMEOUT = Duration.ofSeconds(30)

    fun sync(
        lock: BrandFonts.Lock,
        root: File,
        log: (String) -> Unit,
        download: (String) -> ByteArray = { fetch(it) },
    ) {
        val directory = Files.createDirectories(root.resolve(lock.directory).toPath()).toFile()
        for (face in lock.faces) {
            val destination = directory.resolve(face.file)
            if (destination.isFile && matches(destination.readBytes(), face.sha256)) continue
            val data = download(face.url)
            if (!matches(data, face.sha256)) {
                throw GradleException(
                    "brand-fonts: ${face.url} is ${BrandFonts.sha256(data)}, locked ${face.sha256}; ${face.file} not written",
                )
            }
            val temporary = Files.createTempFile(directory.toPath(), ".${face.file}.", ".download")
            try {
                Files.write(temporary, data)
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
            log("downloaded ${face.file}")
        }
        log("${lock.faces.size} brand fonts installed and verified in ${lock.directory}")
    }

    fun fetch(url: String, sleep: (Long) -> Unit = Thread::sleep): ByteArray {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build()
        val request = HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT).GET().build()
        for (attempt in 1..ATTEMPTS) {
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                when {
                    response.statusCode() in 200..299 -> return response.body()
                    response.statusCode() < 500 || attempt == ATTEMPTS ->
                        throw GradleException("brand-fonts: $url returned ${response.statusCode()}")
                }
            } catch (error: IOException) {
                if (attempt == ATTEMPTS) throw GradleException("brand-fonts: $url failed: $error", error)
            }
            sleep(1000L shl attempt)
        }
        error("unreachable")
    }

    private fun matches(data: ByteArray, sha256: String): Boolean =
        MessageDigest.isEqual(BrandFonts.sha256(data).toByteArray(), sha256.toByteArray())
}
