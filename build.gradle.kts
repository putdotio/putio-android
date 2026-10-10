import groovy.json.JsonSlurper

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
        "mobile/src/main/res/drawable/putio_wordmark.xml",
        fileTree("mobile/src/nightly/res"),
        fileTree("tv/src/nightly/res"),
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

// package.json pins oxfmt so Renovate can bump it; needs Node on PATH.
val oxfmtVersion =
    providers.fileContents(layout.projectDirectory.file("package.json")).asText.map { text ->
        val devDependencies = (JsonSlurper().parseText(text) as? Map<*, *>)?.get("devDependencies") as? Map<*, *>
        devDependencies?.get("oxfmt") as? String ?: error("package.json must pin oxfmt in devDependencies")
    }

val markdownCheck = tasks.register<Exec>("markdownCheck") {
    group = "verification"
    description = "Check Markdown formatting with oxfmt"
    commandLine("npx", "--yes", "oxfmt@${oxfmtVersion.get()}", "--check", "**/*.md")
}

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(
        // Every app and library runs its own lint, detekt and unit tests.
        subprojects.filter { it.buildFile.isFile }.map { "${it.path}:check" },
        ":mobile:assembleProductionRelease",
        ":tv:assembleProductionRelease",
        ":tv:assembleNightlyRelease",
        ":mobile:assembleProductionDebugAndroidTest",
        ":tv:assembleProductionDebugAndroidTest",
        ":domain:auth:assembleDebugAndroidTest",
        ":domain:transfers:assembleDebugAndroidTest",
        checkDesignAssets,
        checkIcons,
        markdownCheck,
        testDesignAssetPipeline,
        testEmulatorHarness,
        testEvidence,
        testIconPipeline,
        testTalkBackInput,
        gradle.includedBuild("build-logic").task(":test"),
    )
}
