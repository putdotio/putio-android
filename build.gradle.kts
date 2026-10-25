import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.play.publisher) apply false
    alias(libs.plugins.sentry.android) apply false
    id("putio.build-logic")
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

val testSignInHarness = tasks.register<Exec>("testSignInHarness") {
    description = "Run the devs-auto device-code sign-in script's contract tests"
    cacheableCheck()
    commandLine("bash", "scripts/test-sign-in.sh")
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
        "core/design/src/main/res/drawable/putio_wordmark.xml",
        fileTree("core/design/src/main/res") { include("drawable-*/putio_icon.png") },
        fileTree("mobile/src/main/res") { include("drawable-*/putio_launcher_art.png") },
        fileTree("mobile/src/nightly/res"),
        fileTree("mobile/src/debug/res"),
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

val testBrandFontPipeline = tasks.register<Exec>("testBrandFontPipeline") {
    description = "Test the brand font lock and sync"
    cacheableCheck("design/brand-fonts.lock.json")
    commandLine("python3", "-B", "scripts/test_brand_fonts.py")
}

val testTalkBackInput =tasks.register<Exec>("testTalkBackInput") {
    description = "Test the TalkBack emulator input protocol"
    cacheableCheck()
    commandLine("python3", "-B", "scripts/test_talkback_input.py")
}

// package.json pins the Node tools so Renovate can bump them; they need Node on PATH.
fun pinnedNodeTool(name: String) =
    providers.fileContents(layout.projectDirectory.file("package.json")).asText.map { text ->
        val devDependencies = (JsonSlurper().parseText(text) as? Map<*, *>)?.get("devDependencies") as? Map<*, *>
        devDependencies?.get(name) as? String ?: error("package.json must pin $name in devDependencies")
    }

val designMdFile = layout.projectDirectory.file("DESIGN.md")
val designTokens = layout.projectDirectory.file("design/tokens.dtcg.json")

val checkDesignMdColors = tasks.register<CheckDesignMdColorsTask>("checkDesignMdColors") {
    group = "verification"
    description = "Verify DESIGN.md's colors block matches the token graph"
    tokensFile.set(designTokens)
    designMd.set(designMdFile)
    stamp.set(layout.buildDirectory.file("verification/checkDesignMdColors.stamp"))
}

tasks.register("updateDesignMdColors") {
    description = "Regenerate DESIGN.md's colors block from the token graph"
    val markdown = designMdFile.asFile
    val tokens = designTokens.asFile
    doLast { markdown.writeText(DesignTokenCodegen.withDesignMdColors(markdown.readText(), tokens.readText())) }
}

val nodeVersion = providers.exec { commandLine("node", "--version") }.standardOutput.asText

val lintDesignMd = tasks.register<Exec>("lintDesignMd") {
    description = "Lint DESIGN.md against the design.md format"
    cacheableCheck("DESIGN.md", "package.json", ".node-version")
    inputs.property("node", nodeVersion)
    commandLine("bash", "scripts/lint-design-md.sh", pinnedNodeTool("@google/design.md").get())
}

val markdownCheck = tasks.register<Exec>("markdownCheck") {
    group = "verification"
    description = "Check Markdown formatting with oxfmt, and the DESIGN.md contract"
    dependsOn(checkDesignMdColors, lintDesignMd)
    commandLine("npx", "--yes", "oxfmt@${pinnedNodeTool("oxfmt").get()}", "--check", "**/*.md")
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
        testBrandFontPipeline,
        testDesignAssetPipeline,
        testEmulatorHarness,
        testEvidence,
        testIconPipeline,
        testSignInHarness,
        testTalkBackInput,
        gradle.includedBuild("build-logic").task(":test"),
    )
}
