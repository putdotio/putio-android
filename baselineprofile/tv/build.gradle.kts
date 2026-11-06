plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.putdotio.android.baselineprofile.tv"
    compileSdk = PutioAndroidSdk.COMPILE

    defaultConfig {
        minSdk = 28
        targetSdk = PutioAndroidSdk.TARGET
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("channel", "production")
    }

    targetProjectPath = ":tv"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
