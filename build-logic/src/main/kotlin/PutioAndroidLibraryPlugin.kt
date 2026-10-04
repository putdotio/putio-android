import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/** Android SDK levels shared by the application and every library module. */
object PutioAndroidSdk {
    const val COMPILE = 37
    const val MIN = 26
    const val TARGET = 37
}

/**
 * Shared setup for the `core` and `domain` Android libraries: SDK levels, the
 * repository's lint and detekt gates, the Compose compiler, and the JVM test stack. Each module still
 * declares its namespace and its own dependencies.
 */
class PutioAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        // Compose infers stability for every module's models, as it did when they compiled in the app.
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<LibraryExtension> {
            compileSdk = PutioAndroidSdk.COMPILE
            defaultConfig.minSdk = PutioAndroidSdk.MIN
            lint {
                warningsAsErrors = true
                abortOnError = true
                lintConfig = rootProject.file("lint.xml")
            }
            // Robolectric-backed tests need the module's resources.
            testOptions.unitTests.isIncludeAndroidResources = true
        }

        useOfflineRobolectric()

        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
            source.setFrom("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val composeBom = dependencies.platform(libs.findLibrary("androidx-compose-bom").get())
        val composeRuntime = libs.findLibrary("androidx-compose-runtime").get()
        // The Compose compiler runs on every compilation, so each needs the runtime, test fixtures included.
        configurations.matching { it.name in setOf("implementation", "testFixturesImplementation") }.configureEach {
            target.dependencies.add(name, composeBom)
            target.dependencies.add(name, composeRuntime)
        }
        for (alias in listOf("junit", "kotlinx-coroutines-test", "robolectric", "androidx-test-ext-junit")) {
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }
    }
}
