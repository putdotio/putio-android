import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.ResValue

fun originalVideoPolicy(): String =
    if (providers.gradleProperty("putioForceOriginalVideo").orNull == "true") "Always" else "Check"

plugins {
    id("putio.android.application")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    defaultConfig {
        applicationId = "io.put.putio"
        buildConfigField("String", "PUTIO_TV_OAUTH_CLIENT_ID_ANDROID_TV", "\"6221\"")
        buildConfigField("String", "PUTIO_TV_OAUTH_CLIENT_ID_FIRE_TV", "\"6233\"")
        buildConfigField("String", "PUTIO_ORIGINAL_VIDEO", "\"Check\"")
    }
    buildTypes {
        debug {
            buildConfigField("String", "PUTIO_ORIGINAL_VIDEO", "\"${originalVideoPolicy()}\"")
        }
    }
    buildFeatures.resValues = true
}

baselineProfile {
    mergeIntoMain = true
}

val playBundle = configurations.consumable("playBundle") {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, PlayBundles.USAGE))
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
    onVariants(selector().withFlavor("channel" to "production").withBuildType("release")) { variant ->
        artifacts.add(playBundle.name, variant.artifacts.get(SingleArtifact.BUNDLE))
    }
    onVariants { variant ->
        variant.resValues.put(
            variant.makeResValueKey("string", "tv_search_authority"),
            ResValue("${variant.applicationId.get()}.search", "System search suggestions provider"),
        )
    }
}

tasks.withType<VerifyLauncherManifestTask>().configureEach {
    launcherCategories.add("android.intent.category.LEANBACK_LAUNCHER")
}

tasks.register<VerifyInstrumentationProofTask>("verifyLaunchProof") {
    group = "verification"
    description = "Require a successful TV launch smoke test result"
    dependsOn("connectedProductionDebugAndroidTest")
    resultsDirectory.set(layout.buildDirectory.dir("outputs/androidTest-results/connected/debug/flavors/production"))
    requiredTest.set("io.putdotio.android.LaunchSmokeTest#shellLaunchesStaysResumedAndRenders")
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
    implementation(project(":domain:trash"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.tv.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.zxing.core)

    baselineProfile(project(":baselineprofile:tv"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:auth")))
    testImplementation(testFixtures(project(":domain:files")))
    testImplementation(testFixtures(project(":domain:history")))
    testImplementation(testFixtures(project(":domain:playback")))
    testImplementation(testFixtures(project(":domain:search")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(testFixtures(project(":domain:files")))
    androidTestImplementation(testFixtures(project(":domain:playback")))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core) {
        because("API 37 removed InputManager.getInstance; Espresso 3.7 uses getSystemService for input injection")
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
