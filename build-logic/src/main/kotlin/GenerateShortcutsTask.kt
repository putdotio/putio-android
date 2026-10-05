import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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

/**
 * Writes `xml/shortcuts.xml` from a template with each `${applicationId}` replaced. The platform
 * reads a static shortcut's `android:targetPackage` literally, without resolving resource
 * references, and every channel and build type installs under its own application id.
 */
@CacheableTask
abstract class GenerateShortcutsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val template: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    /** An Android resource directory. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = outputDir.get().asFile.resolve("xml/shortcuts.xml")
        target.parentFile.mkdirs()
        target.writeText(renderShortcuts(template.get().asFile.readText(), applicationId.get()))
    }
}

internal fun renderShortcuts(template: String, applicationId: String): String {
    if (PLACEHOLDER !in template) throw GradleException("The shortcuts template names no $PLACEHOLDER")
    if (!applicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
        throw GradleException("'$applicationId' is not an application id")
    }
    return template.replace(PLACEHOLDER, applicationId)
}

private const val PLACEHOLDER = "\${applicationId}"
