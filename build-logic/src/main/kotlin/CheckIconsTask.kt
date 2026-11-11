import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CheckIconsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val drawables: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val lock = PhosphorIcons.parseLock(lockFile.get().asFile.readText())
        val drawables = PhosphorIcons.drawablesByPath(repositoryRoot.get().asFile, drawables.files)
        val summary = PhosphorIcons.check(lock, drawables)
        report.get().asFile.writeText(
            lock.icons.joinToString("\n", postfix = "\n") { "${it.outputSha256}  ${it.outputPath}" },
        )
        logger.lifecycle(summary)
    }
}
