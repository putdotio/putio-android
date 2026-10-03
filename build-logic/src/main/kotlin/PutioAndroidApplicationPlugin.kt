import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Shared setup for the `mobile` and `tv` application modules: SDK levels, the
 * `production` and `nightly` channels, release minification, the lint and detekt
 * gates, and the launcher manifest check. Each app declares its application id,
 * surface-specific build config and dependencies.
 */
class PutioAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<ApplicationExtension> {
            namespace = "io.putdotio.android"
            compileSdk = PutioAndroidSdk.COMPILE

            defaultConfig {
                minSdk = PutioAndroidSdk.MIN
                targetSdk = PutioAndroidSdk.TARGET
                versionCode = 1
                versionName = "0.1.0"
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            flavorDimensions += "channel"
            productFlavors {
                create("production") {
                    dimension = "channel"
                    // `lint` checks only the default variant; without this AGP picks nightlyDebug.
                    isDefault = true
                }
                // Play internal/closed tracks ship nightly; the public listing keeps
                // production. Own application id so both install side by side.
                create("nightly") {
                    dimension = "channel"
                    applicationIdSuffix = ".nightly"
                    versionNameSuffix = "-nightly"
                }
            }

            buildTypes {
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                }
                getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }

            lint {
                warningsAsErrors = true
                abortOnError = true
                lintConfig = rootProject.file("lint.xml")
                // AGP skips lintVital's report whenever full lint runs, as it does in verify, yet
                // still runs its analysis on every release variant and library. Full lint with
                // warnings as errors already covers lintVital's fatal-only checks.
                checkReleaseBuilds = false
            }

            // Robolectric-backed Compose tests need the app's resources.
            testOptions.unitTests.isIncludeAndroidResources = true

            buildFeatures.buildConfig = true
        }

        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
            baseline = file("detekt-baseline.xml")
        }

        extensions.configure<ApplicationAndroidComponentsExtension> {
            // Nightly adds resources only, so its unit tests would rerun production's.
            beforeVariants(selector().withFlavor("channel" to "nightly")) { variant ->
                variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
            }

            onVariants { variant ->
                // Launchers find the app with an ACTION_MAIN query; a category without MAIN matches nothing.
                val launcherManifest = tasks.register<VerifyLauncherManifestTask>(
                    "verify${variant.name.replaceFirstChar(Char::titlecase)}LauncherManifest",
                ) {
                    group = "verification"
                    description = "Require launcher entries for MainActivity in the ${variant.name} merged manifest"
                    mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    activity.set("io.putdotio.android.MainActivity")
                    launcherCategories.add("android.intent.category.LAUNCHER")
                }
                tasks.named("check") { dependsOn(launcherManifest) }
            }
        }
    }
}
