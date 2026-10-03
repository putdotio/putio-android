plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.search"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:files"))
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
}
