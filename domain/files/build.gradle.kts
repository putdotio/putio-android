plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.files"

    // Fakes and state builders other modules' tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.putio.sdk.kotlin)
    testImplementation(libs.okhttp)
}
