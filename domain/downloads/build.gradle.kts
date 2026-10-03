plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.downloads"
}

dependencies {
    implementation(project(":domain:files"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
}
