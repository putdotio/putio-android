import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.sentry.android.gradle.extensions.SentryPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Shared setup for the `mobile` and `tv` application modules: SDK levels, the
 * `production` and `nightly` channels, release minification, upload signing, R8 mappings for
 * Sentry, the lint and detekt gates, and the launcher manifest check. Each app declares its application id,
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
                versionCode = putioVersionCode()
                versionName = putioVersionName()
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

            defaultConfig.buildConfigField("String", "PUTIO_SENTRY_DSN", "\"\"")

            // Delivery signs with the Play upload key (docs/distribution.md#signing); without it,
            // release builds stay unsigned and Google Play rejects them.
            providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.let { keystore ->
                val upload = signingConfigs.create("upload") {
                    storeFile = file(keystore)
                    storePassword = requiredEnvironment("ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = requiredEnvironment("ANDROID_KEY_ALIAS")
                    keyPassword = requiredEnvironment("ANDROID_KEY_PASSWORD")
                }
                buildTypes.getByName("release").signingConfig = upload
            }

            buildTypes {
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                }
                getByName("release") {
                    buildConfigField("String", "PUTIO_SENTRY_DSN", "\"${sentryDsn()}\"")
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

        useOfflineRobolectric()
        uploadMappingsToSentry()

        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("detekt.yml"))
            buildUponDefaultConfig = true
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

    /**
     * Only with delivery's `SENTRY_AUTH_TOKEN`: each release build then gets an R8 mapping id Sentry
     * events carry and uploads its mapping under it. Other builds never apply the plugin. Its
     * tracing, SDK installation, optimizations and telemetry stay off, since the SDK lives in
     * core/telemetry.
     */
    private fun Project.uploadMappingsToSentry() {
        val token = providers.environmentVariable("SENTRY_AUTH_TOKEN")
        if (!token.isPresent) return
        pluginManager.apply("io.sentry.android.gradle")
        extensions.configure<SentryPluginExtension> {
            authToken.set(token)
            org.set("putio")
            projectName.set(providers.environmentVariable("SENTRY_PROJECT"))
            ignoredBuildTypes.set(setOf("debug"))
            includeDependenciesReport.set(false)
            includeSourceContext.set(false)
            telemetry.set(false)
            tracingInstrumentation.enabled.set(false)
            autoInstallation.enabled.set(false)
            runtimeOptimizations.enabled.set(false)
        }
    }

    /**
     * Both apps share `io.put.putio`, so delivery passes each its own code; the defaults are for
     * local builds.
     */
    private fun Project.putioVersionCode(): Int {
        val value = providers.gradleProperty("putioVersionCode").getOrElse("1").trim()
        val code = value.toIntOrNull()
        require(code != null && code in 1..MAX_VERSION_CODE) {
            "putioVersionCode must be an integer from 1 to $MAX_VERSION_CODE, got '$value'"
        }
        return code
    }

    private fun Project.putioVersionName(): String {
        val value = providers.gradleProperty("putioVersionName").getOrElse("3.0.0").trim()
        require(VERSION_NAME.matches(value)) { "putioVersionName must look like 3.0.0, got '$value'" }
        return value
    }

    private fun Project.requiredEnvironment(name: String): String =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
            ?: error("ANDROID_KEYSTORE_PATH is set, so $name must be too")

    /**
     * Embedded in a Java string literal, so anything but empty or `https://<key>@<host>/<project id>`
     * fails the build, without printing the value.
     */
    private fun Project.sentryDsn(): String {
        val dsn = providers.gradleProperty("putioSentryDsn")
            .orElse(providers.environmentVariable("PUTIO_SENTRY_DSN"))
            .getOrElse("")
            .trim()
        require(dsn.isEmpty() || SENTRY_DSN.matches(dsn)) {
            "putioSentryDsn must be empty or https://<key>@<host>/<project id>"
        }
        return dsn
    }

    private companion object {
        // Google Play's ceiling.
        const val MAX_VERSION_CODE = 2_100_000_000
        val VERSION_NAME = Regex("""\d{1,4}\.\d{1,4}\.\d{1,4}""")
        val SENTRY_DSN = Regex("""https://[A-Za-z0-9]+@[A-Za-z0-9.-]+/[0-9]+""")
    }
}
