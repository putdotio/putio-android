import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails unless every locked brand font is installed and matches its digest. Release bundles depend
 * on it, so a bundle for Google Play never falls back to the system face.
 */
@CacheableTask
abstract class RequireBrandFontsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val brandFontsLock: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val brandFonts: ConfigurableFileCollection

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val lock = BrandFonts.parseLock(brandFontsLock.get().asFile.readText())
        BrandFonts.installed(lock, brandFonts.files, required = true)
        stamp.get().asFile.writeText("passed\n")
    }
}
