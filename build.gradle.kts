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

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(":app:check", testEvidence, testPublishEvidence)
}
