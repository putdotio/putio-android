import java.util.Properties

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "putio-android"

include(":mobile", ":tv")
include(":baselineprofile:mobile", ":baselineprofile:tv")
include(":core:common", ":core:design", ":core:telemetry")
include(
    ":domain:account",
    ":domain:auth",
    ":domain:files",
    ":domain:history",
    ":domain:playback",
    ":domain:search",
    ":domain:sharing",
    ":domain:transfers",
    ":domain:trash",
)

val localProperties = Properties().apply {
    rootDir.resolve("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { load(it) }
}

localProperties.getProperty("putioSdkKotlinPath")?.let { sdkPath ->
    includeBuild(rootDir.resolve(sdkPath)) {
        dependencySubstitution {
            substitute(module("io.put:putio-sdk-kotlin")).using(project(":"))
        }
    }
}
