import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class LintDesignMdTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val designMd: RegularFileProperty

    @get:Input
    abstract val linterVersion: Property<String>

    @get:Input
    abstract val npx: Property<String>

    @get:Input
    abstract val nodeVersion: Property<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun lint() {
        val design = designMd.get().asFile
        val output = ByteArrayOutputStream()
        val result = exec.exec {
            workingDir = design.parentFile
            commandLine(
                npx.get(), "--yes", "-p", "@google/design.md@${linterVersion.get()}",
                "designmd", "lint", design.name,
            )
            standardOutput = output
            isIgnoreExitValue = true
        }
        val report = output.toString(Charsets.UTF_8)
        logger.lifecycle(report)
        if (result.exitValue != 0) throw GradleException("designmd lint failed on ${design.name}")
        val (errors, warnings) = DesignMdLint.summary(report)
        if (errors + warnings > 0) {
            throw GradleException("designmd lint found $errors errors and $warnings warnings in ${design.name}")
        }
        stamp.get().asFile.writeText("passed\n")
    }
}

object DesignMdLint {
    fun summary(report: String): Pair<Int, Int> {
        val summary = Json.parseToJsonElement(report).jsonObject.getValue("summary").jsonObject
        return summary.getValue("errors").jsonPrimitive.int to summary.getValue("warnings").jsonPrimitive.int
    }
}
