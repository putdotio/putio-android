import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails when DESIGN.md's `colors` block differs from the one the token graph generates. */
@CacheableTask
abstract class CheckDesignMdColorsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokensFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val designMd: RegularFileProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val current = designMd.get().asFile.readText()
        val expected = DesignTokenCodegen.withDesignMdColors(current, tokensFile.get().asFile.readText())
        if (current != expected) {
            throw GradleException("DESIGN.md colors are stale; run ./gradlew updateDesignMdColors")
        }
        stamp.get().asFile.writeText("passed\n")
    }
}
