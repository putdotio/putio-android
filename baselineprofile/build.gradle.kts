// Generates mobile's Baseline Profile; scripts/generate-baseline-profile.sh runs it.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.detekt)
    id("putio.build-logic")
}

android {
    namespace = "io.putdotio.android.baselineprofile"
    compileSdk = PutioAndroidSdk.COMPILE
    targetProjectPath = ":mobile"
    // Profiles target nightly debug, the non-debuggable build that keeps the harness sign-in.
    flavorDimensions += "channel"
    productFlavors { create("nightly") { dimension = "channel" } }

    defaultConfig {
        // BaselineProfileRule collects without root from API 33.
        minSdk = 33
        targetSdk = PutioAndroidSdk.TARGET
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true

    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = rootProject.file("lint.xml")
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
