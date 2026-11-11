import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "It downloads the locked package and writes checked-in drawables")
abstract class GenerateIconsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val drawables: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun generate() {
        val lock = PhosphorIcons.parseLock(lockFile.get().asFile.readText())
        val root = repositoryRoot.get().asFile
        val stale = PhosphorIcons.staleDrawables(lock, PhosphorIcons.drawablesByPath(root, drawables.files))
        val archive = download(lock.pkg.tarball)
        PhosphorIcons.verifyArchive(lock.pkg, archive)
        val tarball = temporaryDir.resolve("package.tgz").apply { writeBytes(archive) }
        val wanted = lock.icons.map { "package/${it.asset}" }.toSet()
        val assets = mutableMapOf<String, ByteArray>()
        archives.tarTree(archives.gzip(tarball)).visit {
            if (!isDirectory && relativePath.pathString in wanted) {
                assets[relativePath.pathString.removePrefix("package/")] = open().use { it.readBytes() }
            }
        }
        PhosphorIcons.render(lock, assets).forEach { (path, output) ->
            val target = root.resolve(path)
            target.parentFile.mkdirs()
            target.writeBytes(output)
            logger.lifecycle("wrote $path")
        }
        stale.forEach { path ->
            root.resolve(path).delete()
            logger.lifecycle("removed $path")
        }
    }

    private fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw GradleException("Phosphor icons: package download failed: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { it.readBytes() }
        } catch (error: IOException) {
            throw GradleException("Phosphor icons: package download failed: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000
    }
}
