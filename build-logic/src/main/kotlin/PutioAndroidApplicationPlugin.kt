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
 * `production` and `nightly` channels, release minification, release-like nightly debug builds,
 * upload signing, R8 mappings for Sentry, the lint and detekt gates, and the launcher manifest
 * check. Each app declares its application id, surface-specific build config and dependencies.
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
                val version = putioVersionName()
                versionCode = putioVersionCode(version)
                versionName = version
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
            defaultConfig.buildConfigField("String", "PUTIO_POSTHOG_TOKEN", "\"\"")

            // Delivery signs with the Play upload key (.github/workflows/play-internal.yml); without it,
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
                    // Only nightly debug builds minify (below); production debug keeps a fast, debuggable build.
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
                getByName("release") {
                    buildConfigField("String", "PUTIO_SENTRY_DSN", "\"${sentryDsn()}\"")
                    // Release only, so debug and test builds never report into the production project.
                    buildConfigField("String", "PUTIO_POSTHOG_TOKEN", "\"$POSTHOG_TOKEN\"")
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
            source.setFrom("src")
        }

        extensions.configure<ApplicationAndroidComponentsExtension> {
            // Nightly adds resources only, so its unit tests would rerun production's.
            beforeVariants(selector().withFlavor("channel" to "nightly")) { variant ->
                variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
            }
            // Testers install nightly debug APKs: they keep the harness sign-in but run like release.
            // BuildConfig.DEBUG follows debuggable, so only production debug honours the local OAuth override.
            beforeVariants(selector().withFlavor("channel" to "nightly").withBuildType("debug")) { variant ->
                variant.isMinifyEnabled = true
                variant.shrinkResources = true
                variant.debuggable = false
                variant.profileable = true
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

                // Only bundles reach Google Play; release APKs, as in verify, may use the system face.
                if (variant.buildType == "release") {
                    val bundle = "bundle${variant.name.replaceFirstChar(Char::titlecase)}"
                    tasks.matching { it.name == bundle }.configureEach { dependsOn(":core:design:requireBrandFonts") }
                }
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
     * Both apps share `io.put.putio`, so one version name gives each its own code:
     * `major * 1_000_000 + minor * 10_000 + patch * 10`, plus 1 for TV. TVs can install either app
     * and Google Play serves the highest code, so TV must take the higher one. Slot 2 belongs to
     * putdotio/putio-tv-native-legacy until it retires; its 3000002 outranks TV 3.0.0, so TV ships
     * from 3.0.1.
     */
    private fun Project.putioVersionCode(versionName: String): Int {
        val (major, minor, patch) = versionName.split('.').map(String::toInt)
        val app = when (path) {
            ":mobile" -> 0
            ":tv" -> 1
            else -> error("No version code slot for $path")
        }
        return major * 1_000_000 + minor * 10_000 + patch * 10 + app
    }

    private fun Project.putioVersionName(): String {
        val value = providers.gradleProperty("putioVersionName").getOrElse("3.0.0").trim()
        require(VERSION_NAME.matches(value) && value.substringBefore('.').toInt() <= MAX_MAJOR) {
            "putioVersionName must look like 3.0.0, with major up to $MAX_MAJOR, minor up to 99 " +
                "and patch up to 999, got '$value'"
        }
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
        // PostHog's public project token, shared with putio-web and iOS; it only names the project.
        const val POSTHOG_TOKEN = "phc_DcWvN8MzbwTfNxki3GhGuBBBDHY5sfvtxfaKZQ3iRDvm" // gitleaks:allow
        // Keeps the highest code under Google Play's 2_100_000_000 ceiling.
        const val MAX_MAJOR = 2_099
        val VERSION_NAME = Regex("""[1-9]\d{0,3}\.(0|[1-9]\d?)\.(0|[1-9]\d{0,2})""")
        val SENTRY_DSN = Regex("""https://[A-Za-z0-9]+@[A-Za-z0-9.-]+/[0-9]+""")
    }
}
