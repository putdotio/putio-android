import java.util.Properties

pluginManagement {
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

include(":app")

val localProperties = Properties().apply {
    rootDir.resolve("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { load(it) }
}

val sdkBuild = rootDir.resolve(
    localProperties.getProperty("putioSdkKotlinPath", "../putio-sdk-kotlin"),
)

includeBuild(sdkBuild) {
    dependencySubstitution {
        substitute(module("io.putdotio:putio-sdk-kotlin")).using(project(":"))
    }
}
