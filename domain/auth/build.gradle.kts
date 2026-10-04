plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.auth"

    // Fakes other modules' tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
