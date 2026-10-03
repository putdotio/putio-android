plugins {
    id("putio.android.application")
    id("putio.build-logic")
}

android {
    defaultConfig {
        // The existing Android TV listing's identity.
        applicationId = "io.put.putio"
        // Device-code OAuth apps from the legacy tv-native lane: one per TV store.
        buildConfigField("String", "PUTIO_TV_OAUTH_CLIENT_ID_ANDROID_TV", "\"6221\"")
        buildConfigField("String", "PUTIO_TV_OAUTH_CLIENT_ID_FIRE_TV", "\"6233\"")
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
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Streams through ExoPlayer, publishes a media session for system and remote controls,
    // and draws subtitles (text and bitmap) with media3-ui's SubtitleView.
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.putio.sdk.kotlin)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:auth")))
    testImplementation(testFixtures(project(":domain:files")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core) {
        because("API 37 removed InputManager.getInstance; Espresso 3.7 uses getSystemService for input injection")
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
