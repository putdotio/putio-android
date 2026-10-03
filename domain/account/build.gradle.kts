plugins {
    id("putio.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.putdotio.android.account"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:files"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.putio.sdk.kotlin)
    implementation(libs.kotlinx.coroutines.android)
}
