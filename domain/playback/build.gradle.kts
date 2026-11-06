plugins {
    id("putio.android.library")
}

val media3Version = libs.versions.media3.get()
val nextlibVersion = libs.versions.nextlib.get()
check(nextlibVersion.startsWith("$media3Version-")) {
    "nextlib $nextlibVersion is built for another Media3 than $media3Version; bump them together"
}

android {
    namespace = "io.putdotio.android.playback"

    // State builders other modules' tests reuse.
    testFixtures.enable = true
    buildFeatures.buildConfig = true
    defaultConfig.buildConfigField("String", "NEXTLIB_VERSION", "\"$nextlibVersion\"")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:telemetry"))
    implementation(project(":domain:account"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.nextlib.media3ext)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(project(":core:common"))
    testFixturesImplementation(libs.androidx.media3.common)
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:account")))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
