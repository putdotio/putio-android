import java.io.File
import java.util.Properties

// Ignored root local.properties key `putioMobileOAuthClientIdDebugOverride`.
// Read and validated at configuration time for every variant, so a malformed
// value fails any build on this machine; only the debug BuildConfig embeds it.
fun localMobileOAuthClientIdOverride(): String {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return ""
    val value = Properties().apply { file.inputStream().use { load(it) } }
        .getProperty("putioMobileOAuthClientIdDebugOverride")
        ?.trim()
        .orEmpty()
    require(value.isEmpty() || (value.all { it.isDigit() } && value.toLongOrNull()?.let { it > 0 } == true)) {
        "putioMobileOAuthClientIdDebugOverride must be a positive integer client id, got '$value'"
    }
    return value
}

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

    flavorDimensions += listOf("surface", "channel")

    productFlavors {
        create("mobile") {
            dimension = "surface"
            applicationIdSuffix = ".mobile"
            versionNameSuffix = "-mobile"
            // Dedicated public Android mobile OAuth client from #47.
            buildConfigField("String", "PUTIO_MOBILE_OAUTH_CLIENT_ID", "\"9677\"")
            // Local harness proof only: an ignored local.properties may point debug
            // builds at another client. The field exists in every variant; the debug
            // build type overrides this empty default with the local value and the
            // runtime honours it only when BuildConfig.DEBUG is true.
            buildConfigField("String", "PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE", "\"\"")
        }

        create("tv") {
            dimension = "surface"
            // Device-code OAuth apps from the legacy tv-native lane: one per TV store.
            buildConfigField("String", "PUTIO_TV_OAUTH_CLIENT_ID_ANDROID_TV", "\"6221\"")
            buildConfigField("String", "PUTIO_TV_OAUTH_CLIENT_ID_FIRE_TV", "\"6233\"")
        }

        create("production") {
            dimension = "channel"
        }

        // Play internal/closed tracks ship nightly; the public listing keeps
        // production. Own application id so both install side by side.
        create("nightly") {
            dimension = "channel"
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField(
                "String",
                "PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE",
                "\"${localMobileOAuthClientIdOverride()}\"",
            )
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
                    testedAbi = "x86_64"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

val generateDesignTokens = tasks.register<GenerateDesignTokensTask>("generateDesignTokens") {
    tokensFile.set(rootProject.layout.projectDirectory.file("design/tokens.dtcg.json"))
    designVersion.set("3.2.1")
    outputDir.set(layout.buildDirectory.dir("generated/designTokens/kotlin"))
}

for (surface in listOf("Mobile", "Tv")) {
    tasks.register<VerifyLaunchProofTask>("verify${surface}LaunchProof") {
        group = "verification"
        description = "Require a successful ${surface.lowercase()} launch smoke test result"
        dependsOn("connected${surface}ProductionDebugAndroidTest")
        resultsDirectory.set(layout.buildDirectory.dir(
            "outputs/androidTest-results/connected/debug/flavors/${surface.lowercase()}Production",
        ))
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateDesignTokens,
            GenerateDesignTokensTask::outputDir,
        )
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.putio.sdk.kotlin)

    add("mobileImplementation", libs.androidx.browser)
    add("mobileImplementation", libs.androidx.media3.exoplayer)
    add("mobileImplementation", libs.androidx.media3.exoplayer.hls)
    add("mobileImplementation", libs.androidx.media3.database)
    add("mobileImplementation", libs.androidx.media3.datasource.okhttp)
    add("mobileImplementation", libs.androidx.media3.session)
    add("mobileImplementation", libs.androidx.media3.ui)
    add("mobileImplementation", libs.androidx.media3.ui.compose.material3)
    add("mobileImplementation", libs.androidx.navigation.compose)
    add("mobileImplementation", libs.coil.compose)
    add("mobileImplementation", libs.coil.network.okhttp)
    add("mobileImplementation", libs.okhttp)
    add("tvImplementation", libs.coil.compose)
    add("tvImplementation", libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core) {
        because("API 37 removed InputManager.getInstance; Espresso 3.7 uses getSystemService for input injection")
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Separate from connectedAndroidTest: preserve the installed app's encrypted session.
tasks.register<RunAuthenticatedRenameProofTask>("proveAuthenticatedRename") {
    group = "verification"
    description = "Run the opted-in authenticated rename flow on an existing API 37 emulator"
    dependsOn("assembleMobileProductionDebug", "assembleMobileProductionDebugAndroidTest")
    proofEnabled.set(providers.gradleProperty("putioRenameEnabled").map { it == "true" }.orElse(false))
    serial.set(providers.gradleProperty("putioRenameSerial").orElse(""))
    val proofRoot = rootProject.projectDir.absolutePath
    fixtureFile.set(rootProject.layout.file(providers.gradleProperty("putioRenameFixture").map {
        File(it).let { path -> if (path.isAbsolute) path else File(proofRoot, it) }
    }))
    repositoryDirectory.set(rootProject.layout.projectDirectory)
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/mobileProduction/debug"))
    testApkDirectory.set(layout.buildDirectory.dir("outputs/apk/androidTest/mobileProduction/debug"))
}
