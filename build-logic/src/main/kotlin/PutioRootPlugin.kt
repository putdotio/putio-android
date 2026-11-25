import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal const val OKHTTP_CLIENT_PATTERN: String =
    """\bOkHttpClient\s*(?:\(|\.\s*Builder\b)|\bokhttp3\.OkHttpClient\.Builder\b|::\s*OkHttpClient\b|\bOkHttpClient\s*::\s*Builder\b|\bimport\s+okhttp3\.OkHttpClient(?:\.\*|\s+as\b)"""

/**
 * Root build conventions: verification tasks (`verify`, `verifyDocs`, `verifyScripts`,
 * `verifyLibraries`, `verifyMobile`, `verifyTv`, `verifyRelease`), design/icon sync tasks, and
 * repository lint gates.
 */
class PutioRootPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "putio.root must be applied to the root project" }
        with(target) {
            val windows = HostBash.isWindows

            val scriptCheckTools = provider { HostBash.path }.flatMap { bash ->
                providers.exec {
                    commandLine(
                        bash, "-c",
                        "for tool in bash python3 ffmpeg ffprobe; do \"\$tool\" --version 2>&1 | head -1; done; shellcheck --version 2>&1 | awk '/^version:/ { print \"shellcheck \" \$2; exit }'",
                    )
                    isIgnoreExitValue = true
                }.standardOutput.asText
            }

            val scripts = fileTree("scripts") { exclude("**/__pycache__/**") }
            val skillScripts = fileTree(".agents/skills/verify-putio-android/scripts") { include("*.sh") }

            val testEvidence = tasks.register<Exec>("testEvidence") {
                description = "Run evidence harness regression tests"
                cacheableCheck(scripts, scriptCheckTools, windows)
                commandLine(HostBash.path, "scripts/test-evidence.sh")
            }

            val testEmulatorHarness = tasks.register<Exec>("testEmulatorHarness") {
                description = "Run emulator provisioning and readiness contract tests"
                cacheableCheck(scripts, scriptCheckTools, windows)
                commandLine(HostBash.path, "scripts/test-emulator.sh")
            }

            val testSignInHarness = tasks.register<Exec>("testSignInHarness") {
                description = "Run the devs-auto device-code sign-in script's contract tests"
                cacheableCheck(scripts, scriptCheckTools, windows)
                commandLine(HostBash.path, "scripts/test-sign-in.sh")
            }

            val testBaselineProfileHarness = tasks.register<Exec>("testBaselineProfileHarness") {
                description = "Run the TV baseline profile script's contract tests"
                cacheableCheck(scripts, scriptCheckTools, windows)
                commandLine(HostBash.path, "scripts/test-baseline-profile.sh")
            }

            val phosphorLock = layout.projectDirectory.file("design/phosphor-icons.lock.json")
            val phosphorDrawables = fileTree(".") {
                include(PhosphorIcons.DRAWABLE_PATTERNS)
                exclude("**/build/**", ".git/**", ".gradle/**", "node_modules/**")
            }

            val checkIcons = tasks.register<CheckIconsTask>("checkIcons") {
                group = "verification"
                description = "Verify locked Phosphor drawables without network access"
                lockFile.set(phosphorLock)
                drawables.from(phosphorDrawables)
                repositoryRoot.set(layout.projectDirectory)
                report.set(layout.buildDirectory.file("verification/checkIcons.txt"))
            }

            tasks.register<GenerateIconsTask>("generateIcons") {
                description = "Regenerate the locked Phosphor drawables from the npm registry"
                lockFile.set(phosphorLock)
                drawables.from(phosphorDrawables)
                repositoryRoot.set(layout.projectDirectory)
            }

            val designAssetsLock = layout.projectDirectory.file(DesignAssets.LOCK_PATH)

            val checkDesignAssets = tasks.register<CheckDesignAssetsTask>("checkDesignAssets") {
                group = "verification"
                description = "Verify locked @putdotio/design assets without network access"
                lockFile.set(designAssetsLock)
                assetFiles.from(DesignAssets.OUTPUT_PATHS)
                repositoryRoot.set(layout.projectDirectory)
                report.set(layout.buildDirectory.file("verification/checkDesignAssets.txt"))
            }

            tasks.register<SyncDesignAssetsTask>("syncDesignAssets") {
                description = "Write the locked @putdotio/design assets from the npm registry"
                lockFile.set(designAssetsLock)
                repositoryRoot.set(layout.projectDirectory)
            }

            tasks.register<SyncBrandFontsTask>("syncBrandFonts") {
                description = "Fetch the licensed brand fonts design/brand-fonts.lock.json pins"
                brandFontsLock.set(layout.projectDirectory.file("design/brand-fonts.lock.json"))
                rootDirectory.set(layout.projectDirectory)
            }

            val testTalkBackInput = tasks.register<Exec>("testTalkBackInput") {
                description = "Test the TalkBack emulator input protocol"
                cacheableCheck(scripts, scriptCheckTools, windows)
                commandLine("python3", "-B", "scripts/test_talkback_input.py")
            }

            val checkDataSafety = tasks.register<Exec>("checkDataSafety") {
                description = "Check the Google Play Data safety CSV for answers Play rejects"
                cacheableCheck(scripts, scriptCheckTools, windows, "play/data-safety.csv")
                commandLine("python3", "-B", "scripts/check-data-safety.py", "play/data-safety.csv")
            }

            val checkShellScripts = tasks.register<Exec>("checkShellScripts") {
                description = "Lint every first-party shell script with shellcheck"
                cacheableCheck(scripts, scriptCheckTools, windows, skillScripts)
                commandLine(
                    HostBash.path,
                    "-c",
                    "shellcheck -x -S warning scripts/*.sh .agents/skills/verify-putio-android/scripts/*.sh",
                )
            }

            val nodePackageJson = layout.projectDirectory.file("package.json")
            val nodePnpmLock = layout.projectDirectory.file("pnpm-lock.yaml")
            val nodeModulesDir = layout.projectDirectory.dir("node_modules")

            val designMdFile = layout.projectDirectory.file("DESIGN.md")
            val designTokens = layout.projectDirectory.file("design/tokens.dtcg.json")

            val checkDesignMdColors = tasks.register<CheckDesignMdColorsTask>("checkDesignMdColors") {
                group = "verification"
                description = "Verify DESIGN.md's colors block matches the token graph"
                tokensFile.set(designTokens)
                designMd.set(designMdFile)
                stamp.set(layout.buildDirectory.file("verification/checkDesignMdColors.stamp"))
            }

            registerUpdateDesignMdColors(designMdFile, designTokens)

            val lintDesignMd = tasks.register<LintDesignMdTask>("lintDesignMd") {
                group = "verification"
                description = "Lint DESIGN.md against the design.md format; warnings fail too"
                designMd.set(designMdFile)
                packageJson.set(nodePackageJson)
                pnpmLock.set(nodePnpmLock)
                nodeModulesDirectory.set(nodeModulesDir)
                val nodeVersionCommand = if (windows) listOf("cmd", "/c", "node", "--version") else listOf("node", "--version")
                nodeVersion.set(providers.exec { commandLine(nodeVersionCommand) }.standardOutput.asText.map { it.trim() })
                stamp.set(layout.buildDirectory.file("verification/lintDesignMd.stamp"))
            }

            val trackedSkillFiles = providers.exec {
                commandLine("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", SkillsLock.SKILLS_PATH)
            }.standardOutput.asText.map { it.split('\u0000').filter(String::isNotEmpty) }

            val checkSkillsLock = tasks.register<CheckSkillsLockTask>("checkSkillsLock") {
                group = "verification"
                description = "Verify the skill files git does not ignore match skills-lock.json"
                repositoryRoot.set(layout.projectDirectory)
                lock.set(layout.projectDirectory.file(SkillsLock.LOCK_PATH))
                trackedFiles.set(trackedSkillFiles)
                skillFiles.from(trackedSkillFiles)
                stamp.set(layout.buildDirectory.file("verification/checkSkillsLock.stamp"))
            }

            val checkSkillLinks = tasks.register<CheckSkillLinksTask>("checkSkillLinks") {
                group = "verification"
                description = "Verify each skill has its .claude/skills link"
                repositoryRoot.set(layout.projectDirectory)
            }

            val markdownCheck = tasks.register<CheckMarkdownTask>("markdownCheck") {
                group = "verification"
                description = "Check Markdown formatting with oxfmt, and the DESIGN.md contract"
                dependsOn(checkDesignMdColors, lintDesignMd)
                repositoryRoot.set(layout.projectDirectory)
                packageJson.set(nodePackageJson)
                pnpmLock.set(nodePnpmLock)
                nodeModulesDirectory.set(nodeModulesDir)
            }

            val checkMiddleCut = tasks.register<CheckForbiddenSourceTask>("checkMiddleCut") {
                group = "verification"
                description = "Forbid TextOverflow.MiddleEllipsis, so the apps keep one middle cut"
                sources.from(subprojects.map { project -> fileTree(project.projectDir.resolve("src")) { include("**/*.kt") } })
                pattern.set("""\bMiddleEllipsis\b""")
                reason.set("Cut names with TvMiddleCutText or MobileMiddleCutText, so the apps keep one middle cut.")
                repositoryRoot.set(layout.projectDirectory)
                stamp.set(layout.buildDirectory.file("verification/checkMiddleCut.stamp"))
            }

            val checkOkHttpClient = tasks.register<CheckForbiddenSourceTask>("checkOkHttpClient") {
                group = "verification"
                description = "Forbid direct OkHttpClient construction outside core/common"
                sources.from(
                    subprojects.filter { it.path != ":core:common" }
                        .map { project ->
                            fileTree(project.projectDir.resolve("src")) {
                                include("**/*.kt")
                                exclude("test/**", "test*/**", "androidTest/**", "androidTest*/**", "testFixtures/**", "testFixtures*/**")
                            }
                        },
                )
                pattern.set(OKHTTP_CLIENT_PATTERN)
                reason.set("Derive HTTP clients from PutioHttpClient.newBuilder() instead of constructing OkHttpClient directly.")
                repositoryRoot.set(layout.projectDirectory)
                stamp.set(layout.buildDirectory.file("verification/checkOkHttpClient.stamp"))
            }

            val appProjects = setOf(":mobile", ":tv", ":baselineprofile:mobile", ":baselineprofile:tv")

            val verifyDocs = verifyLane(
                "verifyDocs",
                "Check Markdown, the DESIGN.md contract, the skills lock and the skill links",
                markdownCheck,
                checkSkillsLock,
                checkSkillLinks,
            )

            val verifyScripts = verifyLane(
                "verifyScripts",
                "Run the harness, design asset, icon, shellcheck and Data safety script checks",
                checkDataSafety,
                checkDesignAssets,
                checkIcons,
                checkShellScripts,
                testBaselineProfileHarness,
                testEmulatorHarness,
                testEvidence,
                testSignInHarness,
                testTalkBackInput,
            )

            val verifyLibraries = tasks.register("verifyLibraries") {
                group = "verification"
                description = "Check the shared libraries and the build logic"
                dependsOn(
                    subprojects.filter { it.buildFile.isFile && it.path !in appProjects }.map { "${it.path}:check" },
                    ":domain:auth:assembleDebugAndroidTest",
                    ":domain:transfers:assembleDebugAndroidTest",
                    checkOkHttpClient,
                    gradle.includedBuild("build-logic").task(":test"),
                )
            }

            val verifyMobile = verifyLane(
                "verifyMobile",
                "Check the mobile app and assemble its instrumented tests",
                ":mobile:check",
                ":baselineprofile:mobile:check",
                ":mobile:assembleProductionDebugAndroidTest",
                checkMiddleCut,
                checkOkHttpClient,
            )

            val verifyTv = verifyLane(
                "verifyTv",
                "Check the TV app and assemble its instrumented tests",
                ":tv:check",
                ":baselineprofile:tv:check",
                ":tv:assembleProductionDebugAndroidTest",
                checkMiddleCut,
                checkOkHttpClient,
            )

            val verifyRelease = verifyLane(
                "verifyRelease",
                "Assemble the minified release builds and check kept R8 symbols",
                ":mobile:assembleProductionRelease",
                ":mobile:verifyProductionReleaseR8Keep",
                ":tv:assembleProductionRelease",
                ":tv:verifyProductionReleaseR8Keep",
                ":tv:assembleNightlyRelease",
                ":tv:verifyNightlyReleaseR8Keep",
            )

            tasks.register("verify") {
                group = "verification"
                description = "Run the canonical local checks"
                dependsOn(verifyDocs, verifyScripts, verifyLibraries, verifyMobile, verifyTv, verifyRelease)
            }
        }
    }
}

private fun Exec.cacheableCheck(
    scripts: ConfigurableFileTree,
    scriptCheckTools: Provider<String>,
    windows: Boolean,
    vararg sources: Any,
) {
    group = "verification"
    inputs.files(scripts, *sources)
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("tools", scriptCheckTools)
    if (windows) environment("MSYS", "winsymlinks:nativestrict")
    val stamp = project.layout.buildDirectory.file("verification/$name.stamp")
    outputs.file(stamp).withPropertyName("stamp")
    outputs.cacheIf { true }
    doLast { stamp.get().asFile.writeText("passed\n") }
}

private fun Project.registerUpdateDesignMdColors(
    designMdFile: RegularFile,
    designTokens: RegularFile,
) {
    tasks.register("updateDesignMdColors") {
        description = "Regenerate DESIGN.md's colors block from the token graph"
        val markdown = designMdFile.asFile
        val tokens = designTokens.asFile
        doLast {
            markdown.writeText(DesignTokenCodegen.withDesignMdColors(markdown.readText(), tokens.readText()))
        }
    }
}

private fun Project.verifyLane(name: String, summary: String, vararg checks: Any): TaskProvider<Task> =
    tasks.register(name) {
        group = "verification"
        description = summary
        dependsOn(*checks)
    }
