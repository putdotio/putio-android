import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/**
 * Runs Robolectric against the android-all jar Gradle resolved, instead of the
 * one Robolectric downloads from Maven Central into `~/.m2` while tests run.
 * That download sat outside every build cache and failed CI on network errors;
 * Gradle's copy is cached with the rest of the dependencies. Robolectric looks
 * the jar up by file name in `robolectric.dependency.dir`, so the directory of
 * Gradle's resolved file is enough.
 */
fun Project.useOfflineRobolectric() {
    val androidAll = configurations.create("robolectricAndroidAll") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.add(androidAll.name, libs.findLibrary("robolectric-android-all").get())
    tasks.withType<Test>().configureEach {
        jvmArgumentProviders.add(RobolectricOfflineArguments(androidAll))
    }
}

class RobolectricOfflineArguments(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val androidAll: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${androidAll.singleFile.parent}",
    )
}
