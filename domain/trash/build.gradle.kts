plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.trash"

    // Fakes the app module's tests reuse.
    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:files"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(project(":domain:files"))
    testFixturesImplementation(libs.putio.sdk.kotlin)
    testFixturesImplementation(libs.kotlinx.coroutines.android)
}
