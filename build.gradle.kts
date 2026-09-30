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

val checkDesignAssets = tasks.register<Exec>("checkDesignAssets") {
    group = "verification"
    description = "Verify locked @putdotio/design assets without network access"
    commandLine("bash", "scripts/sync-design-assets.sh", "--check")
}

val testDesignAssetPipeline = tasks.register<Exec>("testDesignAssetPipeline") {
    group = "verification"
    description = "Test the @putdotio/design lock and drift contracts"
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "scripts/test_design_assets.py")
}

val testTalkBackInput = tasks.register<Exec>("testTalkBackInput") {
    group = "verification"
    description = "Test the TalkBack emulator input protocol"
    commandLine("python3", "-B", "scripts/test_talkback_input.py")
}

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(
        ":app:check",
        ":app:assembleMobileProductionRelease",
        ":app:assembleMobileProductionDebugAndroidTest",
        checkDesignAssets,
        checkIcons,
        testDesignAssetPipeline,
        testEmulatorHarness,
        testEvidence,
        testIconPipeline,
        testTalkBackInput,
        gradle.includedBuild("build-logic").task(":test"),
    )
}
