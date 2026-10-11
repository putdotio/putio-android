plugins {
    id("putio.android.library")
}

android {
    namespace = "io.putdotio.android.telemetry"
}

dependencies {
    // Only this module imports Sentry; detekt's ForbiddenImport rejects it anywhere else.
    implementation(libs.sentry.android.core)
}
