import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

object PlayBundles {
    const val USAGE = "putio-play-bundle"
}

abstract class SyncPlayBundlesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mobileBundle: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val tvBundle: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun sync() {
        val bundles = requirePlayBundles(mapOf("mobile" to mobileBundle.files, "TV" to tvBundle.files))
        fileSystem.sync {
            from(bundles)
            into(outputDirectory)
        }
    }
}

internal fun requirePlayBundles(bundlesByApp: Map<String, Set<File>>): List<File> =
    bundlesByApp.map { (app, files) ->
        val bundles = files.filter { it.isFile && it.extension == "aab" }
        bundles.singleOrNull()
            ?: throw GradleException("A Google Play release needs one $app .aab, found ${bundles.size} in $files")
    }
