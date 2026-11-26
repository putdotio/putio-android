plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.transfers"

    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.putio.sdk.kotlin)
    testFixturesImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
