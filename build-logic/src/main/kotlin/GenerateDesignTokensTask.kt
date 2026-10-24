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

/**
 * Writes PutioDesignTokens.kt and the XML color resources from the vendored DTCG token graph,
 * and PutioScales.kt from DESIGN.md's front matter, embedding the installed brand fonts.
 */
@CacheableTask
abstract class GenerateDesignTokensTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokensFile: RegularFileProperty

    /** The root DESIGN.md, whose front matter owns the type and corner scales. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val designMd: RegularFileProperty

    /** Version of @putdotio/design the vendored JSON came from; recorded in the generated header. */
    @get:Input
    abstract val designVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val brandFontsLock: RegularFileProperty

    /** The lock directory's files, which may be absent; only locked names count. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val brandFonts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** An Android resource directory holding `values/putio_design_colors.xml` and `font/`, the installed brand fonts. */
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
        // A font removed since the last run must not stay packaged.
        res.resolve("font").deleteRecursively()
        brandFonts.files.filter { it.name in installed }.forEach { it.copyTo(res.resolve("font/${it.name}")) }
        if (installed.size < lock.faces.size) {
            logger.lifecycle(
                "Brand fonts: ${installed.size} of ${lock.faces.size} installed; missing families use the system " +
                    "face. Run ./scripts/sync-brand-fonts.sh to embed them.",
            )
        }

        val colors = res.resolve("values/putio_design_colors.xml")
        colors.parentFile.mkdirs()
        colors.writeText(DesignTokenCodegen.generateColorResources(source, designVersion.get()))
    }
}
