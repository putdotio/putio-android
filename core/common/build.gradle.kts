plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.common"

    testFixtures.enable = true
}

dependencies {
    api(libs.okhttp)
    implementation(libs.aboutlibraries.core)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.putio.sdk.kotlin)
}
