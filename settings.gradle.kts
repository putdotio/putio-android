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
include(":core:common", ":core:design")
include(
    ":domain:account",
    ":domain:auth",
    ":domain:files",
    ":domain:history",
    ":domain:playback",
    ":domain:search",
    ":domain:transfers",
    ":domain:trash",
)

val localProperties = Properties().apply {
    rootDir.resolve("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { load(it) }
}

// Opt-in: point putioSdkKotlinPath in local.properties at an SDK checkout to
// build the app against unreleased SDK changes instead of the Maven Central
// release pinned in gradle/libs.versions.toml.
localProperties.getProperty("putioSdkKotlinPath")?.let { sdkPath ->
    includeBuild(rootDir.resolve(sdkPath)) {
        dependencySubstitution {
            substitute(module("io.put:putio-sdk-kotlin")).using(project(":"))
        }
    }
}
