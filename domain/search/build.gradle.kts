plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.search"

    // State builders other modules' tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:files"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(project(":domain:files"))
}
