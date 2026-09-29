import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Writes PutioDesignTokens.kt from the vendored DTCG token graph. */
@CacheableTask
abstract class GenerateDesignTokensTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokensFile: RegularFileProperty

    /** Version of @putdotio/design the vendored JSON came from; recorded in the generated header. */
    @get:Input
    abstract val designVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = tokensFile.get().asFile.readText()
        val code = DesignTokenCodegen.generate(source, designVersion.get())
        val target = outputDir.get().asFile.resolve("io/putdotio/android/design/PutioDesignTokens.kt")
        target.parentFile.mkdirs()
        target.writeText(code)
    }
}
