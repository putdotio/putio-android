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

includeBuild("../putio-sdk-kotlin") {
    dependencySubstitution {
        substitute(module("io.putdotio:putio-sdk-kotlin")).using(project(":"))
    }
}

