import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyDebugOnlyActivityTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val activity: Property<String>

    @get:Input
    abstract val present: Property<Boolean>

    @TaskAction
    fun verify() {
        requireActivityPresence(mergedManifest.get().asFile, activity.get(), present.get())
    }
}

internal fun requireActivityPresence(manifest: File, activity: String, present: Boolean) {
    val declared = manifestActivities(manifest, activity).isNotEmpty()
    when {
        present && !declared -> throw GradleException("${manifest.name} declares no $activity")
        !present && declared -> throw GradleException("${manifest.name} must not declare debug-only $activity")
    }
}
