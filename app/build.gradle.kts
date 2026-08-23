plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "io.putdotio.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.put.putio"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "surface"

    productFlavors {
        create("mobile") {
            dimension = "surface"
            applicationIdSuffix = ".mobile"
            versionNameSuffix = "-mobile"
        }

        create("tv") {
            dimension = "surface"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests {
            // Robolectric-backed Compose tests need the app's resources.
            isIncludeAndroidResources = true
        }

        managedDevices {
            localDevices {
                // CI smoke lane device (workflow_dispatch/scheduled); local
                // proof stays on scripts/prove.sh with the reusable AVDs.
                create("ciPhone") {
                    device = "Pixel 7"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
        }
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.material)
    implementation(libs.putio.sdk.kotlin)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
