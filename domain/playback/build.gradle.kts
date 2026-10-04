plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.playback"

    // State builders other modules' tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:account"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(project(":core:common"))
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:account")))
}
