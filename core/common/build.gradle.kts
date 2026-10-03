plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.common"

    // Fakes the app module's tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.putio.sdk.kotlin)
}
