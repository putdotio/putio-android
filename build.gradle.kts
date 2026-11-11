import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
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
val windows = HostBash.isWindows
val bash: String = HostBash.path

val scriptCheckTools = providers.exec {
    commandLine(
        bash, "-c",
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
    if (windows) environment("MSYS", "winsymlinks:nativestrict")
    val stamp = layout.buildDirectory.file("verification/$name.stamp")
    outputs.file(stamp).withPropertyName("stamp")
    outputs.cacheIf { true }
    doLast { stamp.get().asFile.writeText("passed\n") }
}

val testEvidence = tasks.register<Exec>("testEvidence") {
    description = "Run evidence harness regression tests"
    cacheableCheck()
    commandLine(bash, "scripts/test-evidence.sh")
}

val testEmulatorHarness = tasks.register<Exec>("testEmulatorHarness") {
    description = "Run emulator provisioning and readiness contract tests"
    cacheableCheck()
    commandLine(bash, "scripts/test-emulator.sh")
}

val testSignInHarness = tasks.register<Exec>("testSignInHarness") {
    description = "Run the devs-auto device-code sign-in script's contract tests"
    cacheableCheck()
    commandLine(bash, "scripts/test-sign-in.sh")
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
    commandLine(bash, "scripts/generate-icons.sh", "--check")
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
        "mobile/src/main/res/drawable/putio_launcher_monochrome.xml",
        fileTree("core/design/src/main/res") { include("drawable-*/putio_icon.png") },
        fileTree("mobile/src/main/res") { include("drawable-*/putio_launcher_art.png") },
        fileTree("mobile/src/nightly/res"),
        fileTree("mobile/src/debug/res"),
        fileTree("tv/src/nightly/res"),
    )
    commandLine(bash, "scripts/sync-design-assets.sh", "--check")
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

val lintDesignMd = tasks.register<LintDesignMdTask>("lintDesignMd") {
    group = "verification"
    description = "Lint DESIGN.md against the design.md format; warnings fail too"
    designMd.set(designMdFile)
    linterVersion.set(pinnedNodeTool("@google/design.md"))
    npx.set(if (windows) "npx.cmd" else "npx")
    nodeVersion.set(providers.exec { commandLine("node", "--version") }.standardOutput.asText.map { it.trim() })
    stamp.set(layout.buildDirectory.file("verification/lintDesignMd.stamp"))
}

val markdownCheck = tasks.register<Exec>("markdownCheck") {
    group = "verification"
    description = "Check Markdown formatting with oxfmt, and the DESIGN.md contract"
    dependsOn(checkDesignMdColors, lintDesignMd)
    commandLine(if (windows) "npx.cmd" else "npx", "--yes", "oxfmt@${pinnedNodeTool("oxfmt").get()}", "--check", "**/*.md")
}

val appProjects = setOf(":mobile", ":tv", ":baselineprofile:tv")

fun verifyLane(name: String, summary: String, vararg checks: Any) = tasks.register(name) {
    group = "verification"
    description = summary
    dependsOn(*checks)
}

val verifyDocs = verifyLane("verifyDocs", "Check Markdown and the DESIGN.md contract", markdownCheck)

val verifyScripts = verifyLane(
    "verifyScripts",
    "Run the harness, design asset and icon script checks",
    checkDesignAssets,
    checkIcons,
    testBrandFontPipeline,
    testDesignAssetPipeline,
    testEmulatorHarness,
    testEvidence,
    testIconPipeline,
    testSignInHarness,
    testTalkBackInput,
)

val verifyLibraries = verifyLane(
    "verifyLibraries",
    "Check the shared libraries and the build logic",
    subprojects.filter { it.buildFile.isFile && it.path !in appProjects }.map { "${it.path}:check" },
    ":domain:auth:assembleDebugAndroidTest",
    ":domain:transfers:assembleDebugAndroidTest",
    gradle.includedBuild("build-logic").task(":test"),
)

val verifyMobile = verifyLane(
    "verifyMobile",
    "Check the mobile app and assemble its instrumented tests",
    ":mobile:check",
    ":mobile:assembleProductionDebugAndroidTest",
)

val verifyTv = verifyLane(
    "verifyTv",
    "Check the TV app and assemble its instrumented tests",
    ":tv:check",
    ":baselineprofile:tv:check",
    ":tv:assembleProductionDebugAndroidTest",
)

val verifyRelease = verifyLane(
    "verifyRelease",
    "Assemble the minified release builds",
    ":mobile:assembleProductionRelease",
    ":tv:assembleProductionRelease",
    ":tv:assembleNightlyRelease",
)

tasks.register("verify") {
    group = "verification"
    description = "Run the canonical local checks"
    dependsOn(verifyDocs, verifyScripts, verifyLibraries, verifyMobile, verifyTv, verifyRelease)
}
