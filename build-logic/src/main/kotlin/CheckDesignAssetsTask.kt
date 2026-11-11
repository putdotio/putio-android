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
abstract class CheckDesignAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val lock = DesignAssets.parseLock(lockFile.get().asFile.readText())
        val summary = DesignAssets.check(lock, DesignAssets.filesByPath(repositoryRoot.get().asFile, assetFiles.files))
        report.get().asFile.writeText(
            DesignAssets.lockedOutputs(lock).joinToString("\n", postfix = "\n") { (path, sha256) -> "$sha256  $path" },
        )
        logger.lifecycle(summary)
    }
}
