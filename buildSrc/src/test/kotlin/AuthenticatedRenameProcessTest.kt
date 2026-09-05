import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthenticatedRenameProcessTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun completedFailureSkipAndMissingResultCleanUpOwnedRecorder() {
        for (mode in listOf("assertion", "skipped", "missing-result")) {
            val fixture = fixture(mode)
            val output = runFailure(fixture)
            assertTrue(output, output.contains("Authenticated instrumentation proof failed"))
            assertOwnedCleanup(fixture)
        }
    }

    @Test
    fun hostAdbFailurePreservesInstrumentationWhoseOwnershipIsUnknown() {
        val fixture = fixture("adb-exit")
        val output = runFailure(fixture)
        assertTrue(output, output.contains("instrumentation failed"))
        assertUnownedInstrumentationPreserved(fixture, output)
    }

    @Test
    fun unreadableOwnershipReportsSanitizedFailureAndPreservesInstrumentation() {
        val fixture = fixture("unreadable-ownership")
        val output = runFailure(fixture)
        assertTrue(output, output.contains("CLEANUP FAIL Instrumentation ownership check failed"))
        assertTrue(output, output.contains("Authenticated proof cleanup failed"))
        assertTrue(File(fixture, "state/instrumentation").exists())
        assertFalse(File(fixture, "state/recorder").exists())
        assertTrue(File(fixture, "state/remote-files-removed").isFile)
        assertSafeCommands(fixture)
    }

    @Test
    fun sameRunnerReplacementIsNotMistakenForOwnedInstrumentation() {
        val fixture = fixture("replacement")
        val output = runFailure(fixture)
        assertEquals("foreign-run", File(fixture, "state/instrumentation").readText())
        assertUnownedInstrumentationPreserved(fixture, output)
    }

    @Test
    fun interruptionPreservesUnverifiableInstrumentationAndReportsCleanupFailure() {
        val fixture = fixture("interrupt")
        val output = runFailure(fixture)
        assertTrue(File(fixture, "state/instrumentation-started").isFile)
        assertUnownedInstrumentationPreserved(fixture, output)
        val hostPid = File(fixture, "state/instrumentation-host-pid").readText().trim().toLong()
        assertFalse(ProcessHandle.of(hostPid).map { it.isAlive }.orElse(false))
    }

    @Test(timeout = 120_000)
    fun shutdownHookReportsCaptureRemovalFailureWithoutExposingPayloads() {
        val fixture = fixture("shutdown-remove-failure")
        val testKit = File(fixture, "test-kit").apply { mkdirs() }
        // This build deliberately exits its JVM; keep it away from reusable test daemons.
        assertThrows(Exception::class.java) { runner(fixture).withTestKitDir(testKit).build() }
        assertTrue(File(fixture, "state/instrumentation-started").isFile)
        assertTrue(File(fixture, "state/remove-attempted").isFile)
        assertFalse(File(fixture, "state/remote-files-removed").exists())
        assertFalse(File(fixture, "state/recorder").exists())
        val logs = testKit.walkTopDown().filter { it.isFile && it.name.endsWith(".out.log") }
            .joinToString("\n") { it.readText() }
        assertTrue(logs, logs.contains("CLEANUP FAIL Authenticated proof shutdown cleanup failed"))
        assertFalse(logs, logs.contains("synthetic-sensitive-removal-detail"))
        for (name in listOf("recorder-host-pid", "instrumentation-host-pid", "shutdown-jvm-pid")) {
            val pid = File(fixture, "state/$name").readText().trim().toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
        assertSafeCommands(fixture)
    }

    @Test
    fun recorderStartupFailureDoesNotStartInstrumentation() {
        val fixture = fixture("recorder-exit")
        assertTrue(runFailure(fixture).contains("Recorder exited before capture started"))
        assertFalse(File(fixture, "state/instrumentation-started").exists())
        assertFalse(File(fixture, "state/recorder").exists())
        assertSafeCommands(fixture)
    }

    @Test
    fun missingRecorderPidReportsCleanupFailureWithoutKillingAnUnknownProcess() {
        val fixture = fixture("missing-pid")
        val output = runFailure(fixture)
        assertTrue(output, output.contains("Recorder was not ready within 15 seconds"))
        assertTrue(output, output.contains("Authenticated proof cleanup failed"))
        assertFalse(File(fixture, "state/instrumentation-started").exists())
        assertFalse(File(fixture, "state/commands").readText().contains("kill -"))
        assertFalse(File(fixture, "state/remote-files-removed").exists())
        val hostPid = File(fixture, "state/recorder-host-pid").readText().trim().toLong()
        assertFalse(ProcessHandle.of(hostPid).map { it.isAlive }.orElse(false))
        assertSafeCommands(fixture)
    }

    @Test
    fun wrongAccountAndMissingFixtureFailBeforeInstall() {
        for (mode in listOf("wrong-account", "missing-fixture")) {
            val fixture = fixture(mode)
            runFailure(fixture)
            assertFalse(File(fixture, "state/commands").readText().contains(" install "))
            assertFalse(File(fixture, "state/recorder").exists())
            assertFalse(File(fixture, "state/remote-files-removed").exists())
            assertSafeCommands(fixture)
        }
    }

    @Test
    fun duplicateFixtureKeyFailsBeforeAnyCommand() {
        val fixture = fixture("duplicate-fixture-key")
        val input = File(fixture, "fixture.json")
        input.writeText(input.readText().trimEnd().dropLast(1) + ",\"expectedAccountId\":11}")
        val output = runFailure(fixture)
        assertTrue(output, output.contains("Duplicate fixture field"))
        assertFalse(File(fixture, "state/commands").exists())
    }

    @Test
    fun oversizedFixtureFailsBeforeAnyCommand() {
        val fixture = fixture("oversized-fixture")
        java.io.RandomAccessFile(File(fixture, "fixture.json"), "rw").use { it.setLength(24_577) }
        assertTrue(runFailure(fixture).contains("Fixture exceeds proof input limit"))
        assertFalse(File(fixture, "state/commands").exists())
    }

    @Test
    fun oversizedFinalInstrumentationOutputFailsAndCleansUp() {
        val fixture = fixture("oversized-result")
        assertTrue(runFailure(fixture).contains("output exceeded its limit"))
        assertOwnedCleanup(fixture)
    }

    @Test
    fun missingCliCapabilityFailsBeforeAccountReadsAndInstall() {
        val fixture = fixture("missing-capability")
        assertTrue(runFailure(fixture).contains("putio CLI contract missing or incompatible"))
        val commands = File(fixture, "state/commands").readText()
        assertTrue(commands, commands.contains("putio describe --output json"))
        assertFalse(commands, commands.contains("putio auth"))
        assertFalse(commands, commands.contains(" install "))
        assertFalse(File(fixture, "state/instrumentation-started").exists())
    }

    private fun assertUnownedInstrumentationPreserved(fixture: File, output: String) {
        assertTrue(output, output.contains("Active instrumentation preserved: run ownership cannot be established"))
        assertTrue(output, output.contains("Authenticated proof cleanup failed"))
        assertTrue(File(fixture, "state/instrumentation").exists())
        assertFalse(File(fixture, "state/recorder").exists())
        assertTrue(File(fixture, "state/remote-files-removed").isFile)
        assertSafeCommands(fixture)
    }

    private fun assertOwnedCleanup(fixture: File) {
        assertFalse(File(fixture, "state/instrumentation").exists())
        assertFalse(File(fixture, "state/recorder").exists())
        assertTrue(File(fixture, "state/remote-files-removed").isFile)
        val commands = File(fixture, "state/commands").readText()
        assertTrue(commands, commands.contains("kill -INT 27182"))
        assertSafeCommands(fixture)
    }

    private fun assertSafeCommands(fixture: File) {
        val commands = File(fixture, "state/commands").readText()
        for (forbidden in listOf("am force-stop", "uninstall", "pm clear", "emu kill", "kill-server", "pkill", "killall")) {
            assertFalse(commands, commands.contains(forbidden))
        }
        assertFalse(commands, commands.contains("PROOF PASS"))
    }

    private fun runFailure(fixture: File): String {
        val result = runner(fixture).buildAndFail()
        assertFalse(result.output, result.output.contains("PROOF PASS"))
        return result.output
    }

    private fun runner(fixture: File): GradleRunner {
        val environment = System.getenv().toMutableMap().apply {
            put("PATH", File(fixture, "bin").path + File.pathSeparator + getValue("PATH"))
            put("FAKE_PROOF_DIR", fixture.path)
            put("PUTIO_CLI_TOKEN", "synthetic-token-must-be-removed")
        }
        return GradleRunner.create().withProjectDir(fixture).withEnvironment(environment)
            .withArguments("proof", "--stacktrace", "--console=plain", "--no-configuration-cache")
    }

    private fun fixture(mode: String): File {
        val root = temporaryFolder.newFolder(mode)
        File(root, "state").mkdirs()
        File(root, "state/mode").writeText(mode)
        File(root, "settings.gradle").writeText("rootProject.name = 'rename-process-proof'\n")
        val source = requireNotNull(javaClass.getResource("/rename-proof-command.sh")).readText()
        for (path in listOf("bin/putio", "sdk/platform-tools/adb", "sdk/cmdline-tools/latest/bin/apkanalyzer")) {
            File(root, path).apply { parentFile.mkdirs(); writeText(source); check(setExecutable(true)) }
        }
        File(root, "scripts/lib.sh").apply {
            parentFile.mkdirs()
            writeText("resolve_sdk_root() { printf '%s\\n' \"\$FAKE_PROOF_DIR/sdk\"; }\n")
        }
        File(root, "app/app.apk").apply { parentFile.mkdirs(); writeText("fake APK") }
        File(root, "test/test.apk").apply { parentFile.mkdirs(); writeText("fake APK") }
        File(root, "state/cli-contract.json").writeText(requireNotNull(javaClass.getResource("/rename-proof-cli-contract.json")).readText())
        File(root, "fixture.json").writeText("""
            {"expectedAccountId":11,"containerId":12,"renameItemId":13,"cancelItemId":14,
             "containerName":"Owned container","renameOriginalName":"Rename été",
             "renameNewName":"Renamed 東京","cancelOriginalName":"Cancel me"}
        """.trimIndent())
        val classpath = listOf(RunAuthenticatedRenameProofTask::class.java, Json::class.java, KSerializer::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct().joinToString(", ") { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
        File(root, "build.gradle").writeText("""
            buildscript { dependencies { classpath files($classpath) } }
            tasks.register('proof', RunAuthenticatedRenameProofTask) {
                proofEnabled.set(true)
                serial.set('emulator-5584')
                fixtureFile.set(layout.projectDirectory.file('fixture.json'))
                repositoryDirectory.set(layout.projectDirectory)
                apkDirectory.set(layout.projectDirectory.dir('app'))
                testApkDirectory.set(layout.projectDirectory.dir('test'))
                if ('$mode' == 'interrupt' || '$mode' == 'shutdown-remove-failure') {
                    doFirst {
                        def owner = Thread.currentThread()
                        if ('$mode' == 'shutdown-remove-failure') {
                            file('state/shutdown-jvm-pid').text = ProcessHandle.current().pid().toString()
                        }
                        def marker = file('state/instrumentation-started')
                        def interrupter = new Thread({
                            def deadline = System.nanoTime() + 30_000_000_000L
                            while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(25)
                            if ('$mode' == 'shutdown-remove-failure') System.exit(17)
                            else if (marker.exists()) owner.interrupt()
                        })
                        interrupter.daemon = true
                        interrupter.start()
                    }
                }
            }
        """.trimIndent())
        return root
    }
}
