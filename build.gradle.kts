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

val testEmulatorHarness = tasks.register<Exec>("testEmulatorHarness") {
    group = "verification"
    description = "Run emulator provisioning and readiness contract tests"
    commandLine("bash", "scripts/test-emulator.sh")
}

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(":app:check", testEvidence, testEmulatorHarness)
}
