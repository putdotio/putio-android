import java.io.IOException
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
        val archive = try {
            NpmPackage.download(lock.pkg.tarball)
        } catch (error: IOException) {
            throw GradleException("Phosphor icons: package download failed: ${error.message}", error)
        }
        PhosphorIcons.verifyArchive(lock.pkg, archive)
        val tarball = temporaryDir.resolve("package.tgz").apply { writeBytes(archive) }
        val assets = NpmPackage.unpack(archives, tarball, lock.icons.map { it.asset }.toSet())
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
}
