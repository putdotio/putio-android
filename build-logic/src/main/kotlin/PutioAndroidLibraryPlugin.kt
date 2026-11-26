import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

/** Android SDK levels shared by the application and every library module. */
object PutioAndroidSdk {
    const val COMPILE = 37
    const val COMPILE_MINOR = 1
    const val MIN = 26
    const val TARGET = 37

    fun applyCompileSdk(extension: CommonExtension) {
        extension.compileSdk {
            version = release(COMPILE) {
                minorApiLevel = COMPILE_MINOR
            }
        }
    }
}

/**
 * Shared setup for the `core` and `domain` Android libraries: SDK levels, the
 * repository's lint and detekt gates, Kotlin explicit API mode, the Compose compiler, the JVM
 * test stack and the instrumentation runner. Each module still declares its namespace and its
 * own dependencies.
 */
class PutioAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<LibraryExtension> {
            PutioAndroidSdk.applyCompileSdk(this)
            defaultConfig.minSdk = PutioAndroidSdk.MIN
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            lint {
                warningsAsErrors = true
                abortOnError = true
                lintConfig = rootProject.file("lint.xml")
            }
            testOptions.unitTests.isIncludeAndroidResources = true
        }

        extensions.configure<KotlinAndroidExtension> {
            explicitApi()
        }

        useOfflineRobolectric()
        tasks.withType<Test>().configureEach {
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        }

        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
            source.setFrom("src")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val composeBom = dependencies.platform(libs.findLibrary("androidx-compose-bom").get())
        val composeRuntime = libs.findLibrary("androidx-compose-runtime").get()
        configurations.matching { it.name in setOf("implementation", "testFixturesImplementation") }.configureEach {
            target.dependencies.add(name, composeBom)
            target.dependencies.add(name, composeRuntime)
        }
        for (alias in listOf("junit", "kotlinx-coroutines-test", "robolectric", "androidx-test-ext-junit")) {
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }
    }
}
