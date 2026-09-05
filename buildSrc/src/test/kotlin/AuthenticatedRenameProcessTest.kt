import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthenticatedRenameProcessTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun testFailureSkipAndMissingResultStopOwnedRemoteProcesses() {
        for (mode in listOf("assertion", "skipped", "missing-result")) {
            val fixture = fixture(mode)
            val output = runFailure(fixture)
            assertTrue(output, output.contains("Authenticated instrumentation proof failed"))
            assertOwnedCleanup(fixture)
        }
    }

    @Test
    fun hostAdbFailureStillStopsTheRemoteInstrumentation() {
        val fixture = fixture("adb-exit")
        assertTrue(runFailure(fixture).contains("instrumentation failed"))
        assertOwnedCleanup(fixture)
    }

    @Test
    fun interruptionAfterRemoteStartCleansUpAndPreservesTheSession() {
        val fixture = fixture("interrupt")
        runFailure(fixture)
        assertTrue(File(fixture, "state/instrumentation-started").isFile)
        assertOwnedCleanup(fixture)
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

    private fun assertOwnedCleanup(fixture: File) {
        assertFalse(File(fixture, "state/instrumentation").exists())
        assertFalse(File(fixture, "state/recorder").exists())
        assertTrue(File(fixture, "state/remote-files-removed").isFile)
        val commands = File(fixture, "state/commands").readText()
        assertTrue(commands, commands.contains("am force-stop 'io.put.putio.mobile.debug'"))
        assertTrue(commands, commands.contains("kill -INT 27182"))
        assertSafeCommands(fixture)
    }

    private fun assertSafeCommands(fixture: File) {
        val commands = File(fixture, "state/commands").readText()
        for (forbidden in listOf("uninstall", "pm clear", "emu kill", "kill-server", "pkill", "killall")) {
            assertFalse(commands, commands.contains(forbidden))
        }
        assertFalse(commands, commands.contains("PROOF PASS"))
    }

    private fun runFailure(fixture: File): String {
        val environment = System.getenv().toMutableMap().apply {
            put("PATH", File(fixture, "bin").path + File.pathSeparator + getValue("PATH"))
            put("FAKE_PROOF_DIR", fixture.path)
            put("PUTIO_CLI_TOKEN", "synthetic-token-must-be-removed")
        }
        val result = GradleRunner.create().withProjectDir(fixture).withEnvironment(environment)
            .withArguments("proof", "--stacktrace", "--console=plain", "--no-configuration-cache")
            .buildAndFail()
        assertFalse(result.output, result.output.contains("PROOF PASS"))
        return result.output
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
        File(root, "fixture.json").writeText("""
            {"expectedAccountId":11,"containerId":12,"renameItemId":13,"cancelItemId":14,
             "containerName":"Owned container","renameOriginalName":"Rename me",
             "renameNewName":"Renamed","cancelOriginalName":"Cancel me"}
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
                if ('$mode' == 'interrupt') {
                    doFirst {
                        def owner = Thread.currentThread()
                        def marker = file('state/instrumentation-started')
                        def interrupter = new Thread({
                            def deadline = System.nanoTime() + 30_000_000_000L
                            while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(25)
                            if (marker.exists()) owner.interrupt()
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
