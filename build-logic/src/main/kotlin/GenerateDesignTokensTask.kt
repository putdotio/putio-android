import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateDesignTokensTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokensFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val designMd: RegularFileProperty

    @get:Input
    abstract val designVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val brandFontsLock: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val brandFonts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = tokensFile.get().asFile.readText()
        val code = DesignTokenCodegen.generate(source, designVersion.get())
        val target = outputDir.get().asFile.resolve("io/putdotio/android/design/PutioDesignTokens.kt")
        target.parentFile.mkdirs()
        target.writeText(code)

        val lock = BrandFonts.parseLock(brandFontsLock.get().asFile.readText())
        val installed = BrandFonts.installed(lock, brandFonts.files, required = false)
        val scales = DesignTokenCodegen.generateScales(designMd.get().asFile.readText(), lock, installed)
        target.resolveSibling("PutioScales.kt").writeText(scales)
        val res = resOutputDir.get().asFile
        res.resolve("font").deleteRecursively()
        brandFonts.files.filter { it.name in installed }.forEach { it.copyTo(res.resolve("font/${it.name}")) }
        if (installed.size < lock.faces.size) {
            logger.lifecycle(
                "Brand fonts: ${installed.size} of ${lock.faces.size} installed; missing families use the system " +
                    "face. Run ./gradlew syncBrandFonts to embed them.",
            )
        }

        val colors = res.resolve("values/putio_design_colors.xml")
        colors.parentFile.mkdirs()
        colors.writeText(DesignTokenCodegen.generateColorResources(source, designVersion.get()))
    }
}
