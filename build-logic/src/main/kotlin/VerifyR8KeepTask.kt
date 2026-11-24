import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class VerifyR8KeepTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val seedsFile: RegularFileProperty

    @get:Input
    abstract val requiredClasses: ListProperty<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        requireR8KeptClassesWithFields(seedsFile.get().asFile, requiredClasses.get())
        stamp.get().asFile.writeText("passed\n")
    }
}

internal fun requireR8KeptClassesWithFields(seedsFile: File, requiredClasses: List<String>) {
    if (!seedsFile.isFile) throw GradleException("R8 seeds file missing: ${seedsFile.path}")
    val lines = seedsFile.readLines().map(String::trim).filter(String::isNotEmpty)
    val lineSet = lines.toSet()
    val missing = requiredClasses.filterNot { className ->
        className in lineSet && lines.any { line -> line.startsWith("$className: ") && !line.contains('(') }
    }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "R8 seeds missing kept class or fields (check core/telemetry/consumer-rules.pro): ${missing.joinToString()}",
        )
    }
}
