import java.io.IOException
import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Runs Robolectric against the android-all jars Gradle resolved, instead of the
 * ones Robolectric downloads from Maven Central into `~/.m2` while tests run.
 * That download sat outside every build cache and failed CI on network errors;
 * Gradle's copies are cached with the rest of the dependencies. Robolectric looks
 * each jar up by file name in one `robolectric.dependency.dir`, and Gradle keeps
 * every artifact in its own directory, so the jars are linked into one.
 */
fun Project.useOfflineRobolectric() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    // One configuration per SDK: in a single one Gradle would keep only the newer of two versions.
    val androidAll = files(
        mapOf(
            "robolectricAndroidAll" to "robolectric-android-all",
            "robolectricAndroidAllApi26" to "robolectric-android-all-api26",
        ).map { (name, alias) ->
            configurations.create(name) {
                isCanBeConsumed = false
                isCanBeResolved = true
            }.also { dependencies.add(it.name, libs.findLibrary(alias).get()) }
        },
    )
    val linked = tasks.register<LinkRobolectricAndroidAll>("linkRobolectricAndroidAll") {
        jars.from(androidAll)
        directory.set(layout.buildDirectory.dir("robolectric-android-all"))
    }
    tasks.withType<Test>().configureEach {
        dependsOn(linked)
        jvmArgumentProviders.add(RobolectricOfflineArguments(androidAll, linked.flatMap { it.directory }))
    }
}

/** Links each resolved android-all jar into one directory under its own file name. */
@DisableCachingByDefault(because = "Links files from Gradle's dependency cache; nothing to store")
abstract class LinkRobolectricAndroidAll : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun link() {
        val target = directory.get().asFile.toPath()
        target.toFile().listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
        jars.forEach { jar ->
            val link = target.resolve(jar.name)
            // Robolectric wants a regular file, so a hard link; a copy where the cache is on another volume.
            try {
                Files.createLink(link, jar.toPath())
            } catch (_: IOException) {
                Files.copy(jar.toPath(), link)
            } catch (_: UnsupportedOperationException) {
                Files.copy(jar.toPath(), link)
            }
        }
    }
}

class RobolectricOfflineArguments(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val androidAll: FileCollection,
    @get:Internal
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${directory.get().asFile}",
    )
}
