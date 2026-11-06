plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.telemetry"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.posthog.android)
    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)
    implementation(libs.okhttp)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
