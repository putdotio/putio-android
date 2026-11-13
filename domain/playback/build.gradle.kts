plugins {
    id("putio.android.library")
}

val media3Version = libs.versions.media3.get()
val ffmpegDecoderVersion = libs.versions.media3FfmpegDecoder.get()
val assMediaVersion = libs.versions.assMedia.get()
check(ffmpegDecoderVersion.startsWith("$media3Version+")) {
    "media3-ffmpeg-decoder $ffmpegDecoderVersion is built for another Media3 than $media3Version; bump them together"
}

android {
    namespace = "io.putdotio.android.playback"

    // State builders other modules' tests reuse.
    testFixtures.enable = true
    buildFeatures.buildConfig = true
    defaultConfig.buildConfigField("String", "FFMPEG_DECODER_VERSION", "\"$ffmpegDecoderVersion\"")
    defaultConfig.buildConfigField("String", "ASS_MEDIA_VERSION", "\"$assMediaVersion\"")
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
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.ass.kt)
    implementation(libs.ass.media)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(project(":core:common"))
    testFixturesImplementation(libs.androidx.media3.common)
    testFixturesImplementation(libs.androidx.media3.datasource)
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":domain:account")))
    testImplementation(libs.okhttp)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
