import java.io.File
import java.util.Properties

// Ignored root local.properties key `putioMobileOAuthClientIdDebugOverride`.
// Read and validated at configuration time for every variant, so a malformed
// value fails any build on this machine; only the debug BuildConfig embeds it.
fun localMobileOAuthClientIdOverride(): String {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return ""
    val value = Properties().apply { file.inputStream().use { load(it) } }
        .getProperty("putioMobileOAuthClientIdDebugOverride")
        ?.trim()
        .orEmpty()
    require(value.isEmpty() || (value.all { it.isDigit() } && value.toLongOrNull()?.let { it > 0 } == true)) {
        "putioMobileOAuthClientIdDebugOverride must be a positive integer client id, got '$value'"
    }
    return value
}

plugins {
    id("putio.android.application")
    id("putio.build-logic")
}

android {
    defaultConfig {
        applicationId = "io.put.putio.mobile"
        versionNameSuffix = "-mobile"
        // Dedicated public Android mobile OAuth client from #47.
        buildConfigField("String", "PUTIO_MOBILE_OAUTH_CLIENT_ID", "\"9677\"")
        // Local harness proof only: an ignored local.properties may point debug
        // builds at another client. The field exists in every variant; the debug
        // build type overrides this empty default with the local value and the
        // runtime honours it only when BuildConfig.DEBUG is true.
        buildConfigField("String", "PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE", "\"\"")
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE",
                "\"${localMobileOAuthClientIdOverride()}\"",
            )
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                // CI smoke lane device (workflow_dispatch/scheduled); local
                // proof stays on scripts/prove.sh with the reusable AVDs.
                create("ciPhone") {
                    device = "Pixel 7"
                    apiLevel = 37
                    systemImageSource = "google"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

val launchSmokeTest = "io.putdotio.android.LaunchSmokeTest#shellLaunchesStaysResumedAndRenders"

tasks.register<VerifyInstrumentationProofTask>("verifyLaunchProof") {
    group = "verification"
    description = "Require a successful mobile launch smoke test result"
    dependsOn("connectedProductionDebugAndroidTest")
    resultsDirectory.set(layout.buildDirectory.dir("outputs/androidTest-results/connected/debug/flavors/production"))
    requiredTest.set(launchSmokeTest)
}

// Emulator smoke workflow: one invocation per suite, each gated on its own XML result.
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

// Separate from connectedAndroidTest: preserve the installed app's encrypted session.
tasks.register<RunAuthenticatedRenameProofTask>("proveAuthenticatedRename") {
    group = "verification"
    description = "Run the opted-in authenticated rename flow on an existing API 37 emulator"
    dependsOn("assembleProductionDebug", "assembleProductionDebugAndroidTest")
    proofEnabled.set(providers.gradleProperty("putioRenameEnabled").map { it == "true" }.orElse(false))
    serial.set(providers.gradleProperty("putioRenameSerial").orElse(""))
    val proofRoot = rootProject.projectDir.absolutePath
    fixtureFile.set(rootProject.layout.file(providers.gradleProperty("putioRenameFixture").map {
        File(it).let { path -> if (path.isAbsolute) path else File(proofRoot, it) }
    }))
    repositoryDirectory.set(rootProject.layout.projectDirectory)
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/production/debug"))
    testApkDirectory.set(layout.buildDirectory.dir("outputs/apk/androidTest/production/debug"))
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":domain:account"))
    implementation(project(":domain:auth"))
    implementation(project(":domain:files"))
    implementation(project(":domain:history"))
    implementation(project(":domain:playback"))
    implementation(project(":domain:search"))
    implementation(project(":domain:transfers"))
    implementation(project(":domain:trash"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    // Streams through ExoPlayer, publishes a media session for system and remote controls,
    // draws subtitles (text and bitmap) with media3-ui's SubtitleView, and adds downloads,
    // the background audio service and its controls.
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
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.putio.sdk.kotlin)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:account")))
    testImplementation(testFixtures(project(":domain:auth")))
    testImplementation(testFixtures(project(":domain:files")))
    testImplementation(testFixtures(project(":domain:history")))
    testImplementation(testFixtures(project(":domain:playback")))
    testImplementation(testFixtures(project(":domain:search")))
    testImplementation(testFixtures(project(":domain:transfers")))
    testImplementation(testFixtures(project(":domain:trash")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(testFixtures(project(":domain:account")))
    androidTestImplementation(testFixtures(project(":domain:auth")))
    androidTestImplementation(testFixtures(project(":domain:files")))
    androidTestImplementation(testFixtures(project(":domain:history")))
    androidTestImplementation(testFixtures(project(":domain:playback")))
    androidTestImplementation(testFixtures(project(":domain:search")))
    androidTestImplementation(testFixtures(project(":domain:transfers")))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core) {
        because("API 37 removed InputManager.getInstance; Espresso 3.7 uses getSystemService for input injection")
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
