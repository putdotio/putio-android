plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.account"

    // State builders and fakes other modules' tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:telemetry"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(project(":core:common"))
    testFixturesImplementation(libs.putio.sdk.kotlin)
    testImplementation(libs.okhttp)
}
