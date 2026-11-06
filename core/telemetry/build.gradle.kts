plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.telemetry"
}

dependencies {
    implementation(libs.posthog.android)
    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)
    implementation(libs.okhttp)
}
