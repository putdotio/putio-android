plugins {
    id("putio.android.library")
    id("putio.build-logic")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.putdotio.android.design"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.putio.sdk.kotlin)
}

val generateDesignTokens = tasks.register<GenerateDesignTokensTask>("generateDesignTokens") {
    tokensFile.set(rootProject.layout.projectDirectory.file("design/tokens.dtcg.json"))
    designVersion.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("design/putio-design.lock.json"))
            .asText
            .map { lock ->
                val version = ((groovy.json.JsonSlurper().parseText(lock) as? Map<*, *>)?.get("package") as? Map<*, *>)
                    ?.get("version") as? String
                checkNotNull(version) { "design/putio-design.lock.json has no package.version" }
            },
    )
    outputDir.set(layout.buildDirectory.dir("generated/designTokens/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateDesignTokens,
            GenerateDesignTokensTask::outputDir,
        )
    }
}
