plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.aboutlibraries.gradle.plugin)
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)
    compileOnly(libs.sentry.gradle.plugin)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    inputs.files("../DESIGN.md", "../design/tokens.dtcg.json", "../design/brand-fonts.lock.json", "../design/phosphor-icons.lock.json", "../design/putio-design.lock.json")
        .withPropertyName("designSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "putio.android.application"
            implementationClass = "PutioAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "putio.android.library"
            implementationClass = "PutioAndroidLibraryPlugin"
        }
        register("root") {
            id = "putio.root"
            implementationClass = "PutioRootPlugin"
        }
    }
}
