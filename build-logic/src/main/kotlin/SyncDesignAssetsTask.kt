import java.io.IOException
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "It downloads the locked package and writes checked-in design assets")
abstract class SyncDesignAssetsTask : DefaultTask() {
    @get:Internal
    abstract val lockFile: RegularFileProperty

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun sync() {
        val lock = DesignAssets.parseLock(lockFile.get().asFile.readText())
        val archive = try {
            NpmPackage.download(lock.pkg.tarball)
        } catch (error: IOException) {
            throw GradleException("design-assets: package download failed: ${error.message}", error)
        }
        DesignAssets.sync(lock, repositoryRoot.get().asFile, archive, unpack = { bytes ->
            val tarball = temporaryDir.resolve("package.tgz").apply { writeBytes(bytes) }
            try {
                NpmPackage.unpack(archives, tarball, lock.assets)
            } catch (error: GradleException) {
                throw GradleException("design-assets: locked package is not a readable tarball: ${error.message}", error)
            }
        }, log = { logger.lifecycle(it) })
    }
}
