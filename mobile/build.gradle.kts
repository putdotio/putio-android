import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder
import java.util.Properties

fun localMobileOAuthClientIdOverride(): String {
    val text = providers.fileContents(isolated.rootProject.projectDirectory.file("local.properties")).asText.orNull
        ?: return ""
    val value = Properties().apply { load(text.reader()) }
        .getProperty("putioMobileOAuthClientIdDebugOverride")
        ?.trim()
        .orEmpty()
    require(value.isEmpty() || (value.all { it.isDigit() } && value.toLongOrNull()?.let { it > 0 } == true)) {
        "putioMobileOAuthClientIdDebugOverride must be a positive integer client id, got '$value'"
    }
    return value
}

fun originalVideoPolicy(): String =
    if (providers.gradleProperty("putioForceOriginalVideo").orNull == "true") "Always" else "Check"

plugins {
    id("putio.android.application")
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    defaultConfig {
        applicationId = "io.put.putio"
        buildConfigField("String", "PUTIO_MOBILE_OAUTH_CLIENT_ID", "\"9677\"")
        buildConfigField("String", "PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE", "\"\"")
        buildConfigField("String", "PUTIO_ORIGINAL_VIDEO", "\"Check\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".mobile.debug"
            buildConfigField(
                "String",
                "PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE",
                "\"${localMobileOAuthClientIdOverride()}\"",
            )
            buildConfigField("String", "PUTIO_ORIGINAL_VIDEO", "\"${originalVideoPolicy()}\"")
        }
    }

    sourceSets.named("debug") {
        kotlin.directories.add("src/deviceSignIn/kotlin")
    }

    playConfigs {
        register("productionRelease") {
            enabled.set(true)
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("ciPhone") {
                    device = "Pixel 7"
                    apiLevel = 37
                    systemImageSource = "google"
                }
            }
        }
    }
}

baselineProfile {
    mergeIntoMain = true
}

androidComponents {
    beforeVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
    beforeVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
    onVariants(selector().withFlavor("channel" to "nightly").withBuildType("debug")) { variant ->
        val profiles = checkNotNull(variant.sources.baselineProfiles) { "${variant.name} has no baseline profile sources" }
        profiles.addStaticSourceDirectory(layout.projectDirectory.dir("src/main/generated/baselineProfiles").asFile.absolutePath)
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(
            layout.projectDirectory.dir("src/deviceSignIn/kotlin").asFile.absolutePath,
        )
        variant.sources.manifests.addStaticManifestFile(
            layout.projectDirectory.file("src/nonMinifiedRelease/AndroidManifest.xml").asFile.absolutePath,
        )
    }
    onVariants { variant ->
        val shortcuts = tasks.register<GenerateShortcutsTask>(
            "generate${variant.name.replaceFirstChar(Char::titlecase)}Shortcuts",
        ) {
            template.set(layout.projectDirectory.file("src/main/shortcuts.xml"))
            applicationId.set(variant.applicationId)
            outputDir.set(layout.buildDirectory.dir("generated/shortcuts/${variant.name}"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(shortcuts, GenerateShortcutsTask::outputDir)

        val debugSignIn = tasks.register<VerifyDebugOnlyActivityTask>(
            "verify${variant.name.replaceFirstChar(Char::titlecase)}DebugSignInManifest",
        ) {
            group = "verification"
            description = "Require the device-code sign-in in debug and baseline-profile manifests and its absence from release"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            activity.set("io.putdotio.android.auth.DebugDeviceSignInActivity")
            present.set(variant.buildType == "debug" || variant.buildType == "nonMinifiedRelease")
        }
        tasks.named("check") { dependsOn(debugSignIn) }
    }
}

val tvPlayBundle = configurations.dependencyScope("tvPlayBundle")
val tvPlayBundleFiles = configurations.resolvable("tvPlayBundleFiles") {
    extendsFrom(tvPlayBundle.get())
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, PlayBundles.USAGE))
}

val playBundles = tasks.register<SyncPlayBundlesTask>("syncPlayBundles") {
    description = "Collect the mobile and TV production bundles for one Google Play release"
    dependsOn("bundleProductionRelease", ":tv:bundleProductionRelease")
    tvBundle.from(tvPlayBundleFiles)
    outputDirectory.set(layout.buildDirectory.dir("play-bundles"))
}

androidComponents {
    onVariants(selector().withFlavor("channel" to "production").withBuildType("release")) { variant ->
        playBundles.configure { mobileBundle.from(variant.artifacts.get(SingleArtifact.BUNDLE)) }
    }
}

play {
    enabled.set(false)
    defaultToAppBundles.set(true)
    track.set("internal")
    artifactDir.set(layout.buildDirectory.dir("play-bundles"))
}

tasks.matching { it.name == "publishProductionReleaseBundle" }.configureEach {
    dependsOn(playBundles, "lintProductionRelease", ":tv:lintProductionRelease")
}

val launchSmokeTest = "io.putdotio.android.LaunchSmokeTest#shellLaunchesStaysResumedAndRenders"

mapOf(
    "Launch" to launchSmokeTest,
    "OAuth" to "io.putdotio.android.auth.StaleOAuthCallbackTest#" +
        "staleCallbackPreservesNewerAttemptAndMatchingMalformedCallbackConsumesIt",
).forEach { (suite, test) ->
    tasks.register<VerifyInstrumentationProofTask>("verifyCiPhone${suite}Proof") {
        group = "verification"
        description = "Require a successful $test result on the ciPhone managed device"
        dependsOn("ciPhoneProductionDebugAndroidTest")
        resultsDirectory.set(layout.buildDirectory.dir(
            "outputs/androidTest-results/managedDevice/debug/flavors/production/ciPhone",
        ))
        requiredTest.set(test)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:telemetry"))
    implementation(project(":domain:account"))
    implementation(project(":domain:auth"))
    implementation(project(":domain:files"))
    implementation(project(":domain:history"))
    implementation(project(":domain:playback"))
    implementation(project(":domain:search"))
    implementation(project(":domain:sharing"))
    implementation(project(":domain:transfers"))
    implementation(project(":domain:trash"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.putio.sdk.kotlin)

    baselineProfile(project(":baselineprofile:mobile"))
    tvPlayBundle(project(":tv"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:account")))
    testImplementation(testFixtures(project(":domain:auth")))
    testImplementation(testFixtures(project(":domain:files")))
    testImplementation(testFixtures(project(":domain:history")))
    testImplementation(testFixtures(project(":domain:playback")))
    testImplementation(testFixtures(project(":domain:search")))
    testImplementation(testFixtures(project(":domain:sharing")))
    testImplementation(testFixtures(project(":domain:transfers")))
    testImplementation(testFixtures(project(":domain:trash")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.window.testing)

    androidTestImplementation(testFixtures(project(":domain:account")))
    androidTestImplementation(testFixtures(project(":domain:auth")))
    androidTestImplementation(testFixtures(project(":domain:files")))
    androidTestImplementation(testFixtures(project(":domain:history")))
    androidTestImplementation(testFixtures(project(":domain:playback")))
    androidTestImplementation(testFixtures(project(":domain:search")))
    androidTestImplementation(testFixtures(project(":domain:sharing")))
    androidTestImplementation(testFixtures(project(":domain:transfers")))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core) {
        because("API 37 removed InputManager.getInstance; Espresso 3.7 uses getSystemService for input injection")
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
