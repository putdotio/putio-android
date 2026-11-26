plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.sharing"

    testFixtures.enable = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(testFixtures(project(":core:common")))
    testFixturesImplementation(project(":core:common"))
    testFixturesImplementation(libs.putio.sdk.kotlin)
}
