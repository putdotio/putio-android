plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.telemetry"
}

dependencies {
    implementation(libs.sentry.android.core)
}
