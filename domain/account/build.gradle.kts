plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.account"

    // State builders other modules' tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
}
