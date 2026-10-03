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
 * repository's lint and detekt gates, and the JVM test stack. Each module still
 * declares its namespace and its own dependencies.
 */
class PutioAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
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

        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        for (alias in listOf("junit", "kotlinx-coroutines-test", "robolectric", "androidx-test-ext-junit")) {
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }
    }
}
