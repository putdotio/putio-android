plugins {
    `kotlin-dsl`
}

dependencies {
    // The root build puts AGP, detekt and the Sentry plugin on the classpath; the convention plugin only compiles against them.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)
    compileOnly(libs.sentry.gradle.plugin)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
}

// The codegen tests read the repository's design contract, token graph and font lock.
tasks.test {
    inputs.files("../DESIGN.md", "../design/tokens.dtcg.json", "../design/brand-fonts.lock.json")
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
    }
}
