plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.account"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:files"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
}
