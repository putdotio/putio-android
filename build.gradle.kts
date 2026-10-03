plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Script checks are deterministic in the repository files they read and the host
// tools they run. Declaring both lets Gradle skip an unchanged check and restore a
// passing result from the build cache; a failure writes no stamp, so it never caches.
val scriptCheckTools = providers.exec {
    commandLine(
        "sh", "-c",
        "for tool in bash python3 ffmpeg ffprobe; do \"\$tool\" --version 2>&1 | head -1; done",
    )
    isIgnoreExitValue = true
}.standardOutput.asText

val scripts = fileTree("scripts") { exclude("**/__pycache__/**") }

fun Exec.cacheableCheck(vararg sources: Any) {
    group = "verification"
    inputs.files(scripts, *sources)
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("tools", scriptCheckTools)
    val stamp = layout.buildDirectory.file("verification/$name.stamp")
    outputs.file(stamp).withPropertyName("stamp")
    outputs.cacheIf { true }
    doLast { stamp.get().asFile.writeText("passed\n") }
}

val testEvidence = tasks.register<Exec>("testEvidence") {
    description = "Run evidence harness regression tests"
    cacheableCheck()
    commandLine("bash", "scripts/test-evidence.sh")
}

val testEmulatorHarness = tasks.register<Exec>("testEmulatorHarness") {
    description = "Run emulator provisioning and readiness contract tests"
    cacheableCheck()
    commandLine("bash", "scripts/test-emulator.sh")
}

val checkIcons = tasks.register<Exec>("checkIcons") {
    description = "Verify locked Phosphor drawables without network access"
    cacheableCheck(
        "design/phosphor-icons.lock.json",
        // Every module's drawables: the check fails on stale generated icons anywhere.
        fileTree(".") {
            include("*/src/*/res/drawable/ic_ph_*.xml", "*/*/src/*/res/drawable/ic_ph_*.xml")
            exclude("**/build/**", ".git/**", ".gradle/**")
        },
    )
    commandLine("bash", "scripts/generate-icons.sh", "--check")
}

val testIconPipeline = tasks.register<Exec>("testIconPipeline") {
    description = "Test the Phosphor lock and drift contracts"
    cacheableCheck()
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "scripts/test_phosphor_icons.py")
}

val checkDesignAssets = tasks.register<Exec>("checkDesignAssets") {
    description = "Verify locked @putdotio/design assets without network access"
    cacheableCheck(
        fileTree("design"),
        "app/src/mobile/res/drawable/putio_wordmark.xml",
        fileTree("app/src/nightly/res"),
    )
    commandLine("bash", "scripts/sync-design-assets.sh", "--check")
}

val testDesignAssetPipeline = tasks.register<Exec>("testDesignAssetPipeline") {
    description = "Test the @putdotio/design lock and drift contracts"
    cacheableCheck()
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine("python3", "scripts/test_design_assets.py")
}

val testTalkBackInput = tasks.register<Exec>("testTalkBackInput") {
    description = "Test the TalkBack emulator input protocol"
    cacheableCheck()
    commandLine("python3", "-B", "scripts/test_talkback_input.py")
}

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(
        // Each core and domain library runs its own lint, detekt and unit tests.
        subprojects.filter { it.path != ":app" && it.buildFile.isFile }.map { "${it.path}:check" },
        ":app:check",
        // `check` lints only the default mobile variant.
        ":app:lintTvProductionDebug",
        ":app:assembleMobileProductionRelease",
        ":app:assembleTvProductionRelease",
        ":app:assembleTvNightlyRelease",
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
