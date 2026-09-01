plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val testEvidence = tasks.register<Exec>("testEvidence") {
    group = "verification"
    description = "Run evidence harness regression tests"
    commandLine("bash", "scripts/test-evidence.sh")
}

val testPublishEvidence = tasks.register<Exec>("testPublishEvidence") {
    group = "verification"
    description = "Run Attach evidence publishing regression tests"
    commandLine("bash", "scripts/test-publish-evidence.sh")
}

val checkIcons = tasks.register<Exec>("checkIcons") {
    group = "verification"
    description = "Verify locked Phosphor drawables without network access"
    commandLine("bash", "scripts/generate-icons.sh", "--check")
}

val testIconPipeline = tasks.register<Exec>("testIconPipeline") {
    group = "verification"
    description = "Test the Phosphor lock and drift contracts"
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "scripts/test_phosphor_icons.py")
}

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(
        ":app:check",
        ":app:assembleMobileProductionRelease",
        checkIcons,
        testEvidence,
        testIconPipeline,
        testPublishEvidence,
    )
}
