plugins {
    `kotlin-dsl`
}

dependencies {
    // The root build puts AGP and detekt on the classpath; the convention plugin only compiles against them.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "putio.android.library"
            implementationClass = "PutioAndroidLibraryPlugin"
        }
    }
}
