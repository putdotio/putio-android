import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CheckForbiddenSourceTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val pattern: Property<String>

    @get:Input
    abstract val reason: Property<String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val regex = Regex(pattern.get())
        val root = repositoryRoot.get().asFile
        val hits = sources.files.sorted().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}".takeIf { regex.containsMatchIn(line) }
            }
        }
        if (hits.isNotEmpty()) throw GradleException("${reason.get()}\n${hits.joinToString("\n")}")
        stamp.get().asFile.writeText("passed\n")
    }
}
