import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class RunAuthenticatedRenameProofTask : DefaultTask() {
    @get:Input abstract val proofEnabled: Property<Boolean>
    @get:Input abstract val serial: Property<String>
    @get:InputFile abstract val fixtureFile: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:Internal abstract val apkDirectory: DirectoryProperty
    @get:Internal abstract val testApkDirectory: DirectoryProperty

    @TaskAction
    fun prove() {
        requireProof(proofEnabled.getOrElse(false), "Explicit putioRenameEnabled=true is required")
        val device = serial.getOrElse("")
        requireProof(device.matches(Regex("emulator-[0-9]+")), "An explicit existing emulator serial is required")
        val root = repositoryDirectory.get().asFile
        val lockFile = File(System.getProperty("java.io.tmpdir"), "putio-rename-proof-$device.lock")
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: throw GradleException("Another rename proof owns this serial")
            lock.use {
                AuthenticatedRenameRun(root, device) { logger.lifecycle(it) }.run(
                    fixtureFile.get().asFile, singleApk(apkDirectory.get().asFile),
                    singleApk(testApkDirectory.get().asFile),
                )
            }
        }
    }

    private fun singleApk(directory: File): File =
        directory.listFiles()?.filter { it.isFile && it.extension == "apk" }?.singleOrNull()
            ?: throw GradleException("Expected one assembled APK in ${directory.name}")
}

private class AuthenticatedRenameRun(
    private val root: File,
    private val serial: String,
    private val report: (String) -> Unit,
) {
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
    private val runId = UUID.randomUUID().toString()
    private val evidence = File(root, ".evidence/rename-$runId")
    private val remoteCapture = "/data/local/tmp/putio-rename-$runId.mp4"
    private val remotePid = "/data/local/tmp/putio-rename-$runId.pid"
    private lateinit var adb: String
    private var recorder: Process? = null
    private var recordingStarted = false
    private var recorderPid: Long? = null
    private var instrumentation: Process? = null
    private var instrumentationStarted = false
    private var cleanupDeadline = 0L
    private var targetPackage: String? = null
    private var runnerComponent: String? = null
    private var completed = false

    fun run(fixtureFile: File, app: File, test: File) {
        val cleanupHook = Thread({ cleanup() }, "putio-rename-cleanup-$runId")
        Runtime.getRuntime().addShutdownHook(cleanupHook)
        var failure: Throwable? = null
        var validatedEvidence: String? = null
        try {
            val raw = fixtureFile.readBytes()
            requireProof(raw.size <= 24_576, "Fixture exceeds proof input limit")
            val fixture = parseProofObject(raw.decodeToString(throwOnInvalidSequence = true), "fixture")
            validateRenameFixture(fixture)
            val sdk = command("SDK resolution", listOf(
                "bash", "-c", "source \"\$1\"; resolve_sdk_root", "--", File(root, "scripts/lib.sh").path,
            )).trim()
            adb = File(sdk, "platform-tools/adb").path
            requireProof(command("device state", listOf(adb, "-s", serial, "get-state")).trim() == "device", "Serial is not running")
            requireProof(shell("API level", "getprop ro.build.version.sdk").trim() == "37", "Authenticated proof requires API 37")
            validateCliFixture(fixture)
            val analyzer = File(sdk, "cmdline-tools/latest/bin/apkanalyzer")
            requireProof(analyzer.canExecute(), "apkanalyzer is missing; run repository bootstrap")
            val appIdentity = parseProofManifest(command("app metadata", listOf(analyzer.path, "manifest", "print", app.path)))
            val testIdentity = parseProofManifest(command("test metadata", listOf(analyzer.path, "manifest", "print", test.path)))
            requireProof(appIdentity.packageName == "io.put.putio.mobile.debug", "Wrong application flavor")
            requireProof(testIdentity.targetPackage == appIdentity.packageName, "Test APK targets a different app")
            targetPackage = appIdentity.packageName
            val runner = testIdentity.runner ?: throw GradleException("Test APK has no instrumentation runner")
            runnerComponent = "${testIdentity.packageName}/$runner"
            requireProof(activeInstrumentation(cleanup = false).isEmpty(), "Existing instrumentation already owns the target app")
            command("install app", listOf(adb, "-s", serial, "install", "-r", app.path))
            command("install tests", listOf(adb, "-s", serial, "install", "-r", test.path))
            evidence.mkdirs()
            startRecording()
            val encoded = Base64.getEncoder().encodeToString(raw)
            val args = listOf("am", "instrument", "-w", "-r", "-e", "class", "$PROOF_CLASS#$PROOF_METHOD",
                "-e", "putio.rename.enabled", "true", "-e", "putio.rename.fixture", encoded,
                requireNotNull(runnerComponent))
            val output = File(evidence, "instrumentation.txt")
            synchronized(this) {
                instrumentation = start(listOf(adb, "-s", serial, "shell", args.joinToString(" ", transform = ::proofShellQuote)), output)
                instrumentationStarted = true
            }
            val running = requireNotNull(instrumentation)
            waitFor(running, "instrumentation", output)
            instrumentation = null
            val result = output.readText()
            requireProof(result.length <= MAX_OUTPUT_BYTES, "Instrumentation output exceeded its limit")
            requireSuccessfulInstrumentation(result, PROOF_CLASS, PROOF_METHOD)
            requireProof(activeInstrumentation(cleanup = false).isEmpty(), "Remote instrumentation remained active after completion")
            instrumentationStarted = false
            stopRecording()
            val rawCapture = File(evidence, "capture.mp4.raw")
            command("pull recording", listOf(adb, "-s", serial, "pull", remoteCapture, rawCapture.path))
            val validated = command("validate recording", listOf(
                File(root, "scripts/evidence.sh").path, "validate-recording", "--input", rawCapture.path,
                "--label", "authenticated-rename-$runId",
            )).trim().lineSequence().last()
            requireProof(File(validated).isFile && validated.endsWith(".mp4"), "Validated evidence path missing")
            completed = true
            validatedEvidence = validated
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val clean = cleanup()
            Runtime.getRuntime().removeShutdownHook(cleanupHook)
            if (!clean) {
                val cleanupFailure = GradleException("Authenticated proof cleanup failed; inspect owned run $runId")
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
        requireProof(completed, "Authenticated rename proof did not complete")
        report("EVIDENCE ${requireNotNull(validatedEvidence)}")
        report("PROOF PASS authenticated-rename")
    }

    private fun validateCliFixture(fixture: JsonObject) {
        // Gradle daemons can outlive a PATH change; resolve against this build's environment.
        val cliExecutable = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "putio") }.firstOrNull { it.isFile && it.canExecute() }
            ?: throw GradleException("putio CLI is missing from PATH")
        fun cli(vararg args: String) = parseProofObject(command("CLI preflight", listOf(cliExecutable.absolutePath) + args, cli = true), "CLI response")
        val auth = cli("auth", "status", "--profile", "devs-auto", "--output", "json")
        requireProof(auth["authenticated"] == JsonPrimitive(true) && proofText(auth, "source") == "profile" &&
            proofText(auth, "profile") == "devs-auto" && proofText(auth, "apiBaseUrl") == "https://api.put.io",
            "CLI requires the devs-auto profile on the production API")
        val account = cli("whoami", "--fields", "info", "--output", "json")["info"] as? JsonObject
            ?: throw GradleException("CLI account response missing info")
        requireProof(proofId(account, "user_id") == proofId(fixture, "expectedAccountId") &&
            proofText(account, "username") == "devs-auto", "CLI identity does not match fixture owner")
        val listing = cli("files", "list", "--parent-id", proofId(fixture, "containerId").toString(),
            "--per-page", "50", "--fields", "parent,files,cursor", "--output", "json")
        val parent = listing["parent"] as? JsonObject ?: throw GradleException("CLI fixture parent missing")
        requireProof(proofId(parent, "id") == proofId(fixture, "containerId") &&
            proofText(parent, "name") == proofText(fixture, "containerName") &&
            proofText(parent, "file_type") == "FOLDER", "CLI fixture container mismatch")
        val cursor = listing["cursor"]
        requireProof(cursor == null || cursor == JsonNull || (cursor as? JsonPrimitive)?.content.isNullOrBlank(), "Fixture listing must be complete")
        val files = listing["files"] as? JsonArray ?: throw GradleException("CLI fixture files missing")
        requireProof(files.size <= 50, "Fixture exceeds bounded listing")
        for ((idKey, nameKey) in listOf("renameItemId" to "renameOriginalName", "cancelItemId" to "cancelOriginalName")) {
            val item = files.filterIsInstance<JsonObject>().singleOrNull { proofId(it, "id") == proofId(fixture, idKey) }
                ?: throw GradleException("CLI fixture item missing or ambiguous")
            val name = proofText(fixture, nameKey)
            requireProof(proofText(item, "name") == name && proofId(item, "parent_id") == proofId(fixture, "containerId"), "CLI fixture item mismatch")
            requireProof(files.filterIsInstance<JsonObject>().count { proofText(it, "name") == name } == 1, "CLI fixture name is ambiguous")
        }
        requireProof(files.filterIsInstance<JsonObject>().none { proofText(it, "name") == proofText(fixture, "renameNewName") },
            "CLI replacement name already exists")
        report("PREFLIGHT PASS devs-auto fixture")
    }

    private fun startRecording() {
        val out = File(evidence, "recorder.txt")
        // The shell PID survives exec, so the owner record names the actual screenrecord process.
        val script = "echo \$\$ > ${proofShellQuote(remotePid)}; exec screenrecord --time-limit 180 ${proofShellQuote(remoteCapture)}"
        synchronized(this) {
            recorder = start(listOf(adb, "-s", serial, "shell", "sh -c ${proofShellQuote(script)}"), out)
            recordingStarted = true
        }
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < readyDeadline) {
            requireProof(requireNotNull(recorder).isAlive, "Recorder exited before capture started")
            val pid = shell("recorder owner", "cat ${proofShellQuote(remotePid)}", allowFailure = true).trim().toLongOrNull()
            if (pid != null && pid > 0 && shell("recorder readiness", "test -s ${proofShellQuote(remoteCapture)} && echo ready", allowFailure = true).trim() == "ready") {
                recorderPid = pid
                requireProof(ownsRecorder(pid, cleanup = false), "Recorder ownership could not be verified")
                return
            }
            Thread.sleep(100)
        }
        throw GradleException("Recorder was not ready within 15 seconds")
    }

    private fun ownsRecorder(pid: Long, cleanup: Boolean): Boolean {
        val cmdline = shell("recorder identity",
            "if test -d /proc/$pid; then cat /proc/$pid/cmdline; else printf absent; fi", cleanup = cleanup)
        return cmdline.split('\u0000').let { it.firstOrNull()?.substringAfterLast('/') == "screenrecord" && remoteCapture in it }
    }

    private fun stopRecording() {
        val pid = recorderPid ?: throw GradleException("Recorder owner is missing")
        requireProof(ownsRecorder(pid, cleanup = false), "Recorder ownership changed")
        shell("stop recording", "kill -INT $pid")
        val running = recorder ?: throw GradleException("Recorder process is missing")
        requireProof(running.waitFor(minOf(10_000L, remainingMillis()), TimeUnit.MILLISECONDS), "Recorder did not stop within 10 seconds")
        requireProof(!ownsRecorder(pid, cleanup = false), "Remote recorder remained alive after its host client exited")
        recorder = null
        recorderPid = null
    }

    private fun activeInstrumentation(cleanup: Boolean): Set<String> = activeProofInstrumentation(
        shell("active instrumentation", "dumpsys activity processes ${proofShellQuote(requireNotNull(targetPackage))}", cleanup = cleanup),
    )

    @Synchronized
    private fun cleanup(): Boolean {
        val interrupted = Thread.interrupted()
        cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        return try {
            cleanupOwnedProcesses()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun cleanupOwnedProcesses(): Boolean {
        if (!::adb.isInitialized) return true
        var clean = true
        fun attempt(block: () -> Unit) {
            try { block() } catch (_: Exception) { clean = false }
        }
        if (instrumentationStarted) attempt {
            val active = activeInstrumentation(cleanup = true)
            if (active == setOf(runnerComponent)) {
                shell("stop owned instrumentation", "am force-stop ${proofShellQuote(requireNotNull(targetPackage))}", cleanup = true)
                requireProof(activeInstrumentation(cleanup = true).isEmpty(), "Owned instrumentation did not stop")
            } else {
                requireProof(active.isEmpty(), "Cannot verify exclusive instrumentation ownership during cleanup")
            }
        }
        attempt { instrumentation?.let(::reapHostProcess) }
        instrumentation = null
        instrumentationStarted = false
        if (recordingStarted) attempt {
            val pid = recorderPid ?: shell("recorder cleanup owner", "cat ${proofShellQuote(remotePid)}", allowFailure = true, cleanup = true).trim().toLongOrNull()
            requireProof(pid != null || recorder?.isAlive != true, "Cannot recover live recorder ownership")
            if (pid != null && !ownsRecorder(pid, cleanup = true) && recorder?.isAlive == true) {
                throw GradleException("Live recorder ownership could not be verified")
            }
            if (pid != null && ownsRecorder(pid, cleanup = true)) {
                shell("stop owned recorder", "kill -INT $pid", allowFailure = true, cleanup = true)
                if (recorder?.waitFor(2, TimeUnit.SECONDS) == false && ownsRecorder(pid, cleanup = true)) {
                    shell("stop stalled owned recorder", "kill -KILL $pid", cleanup = true)
                }
                requireProof(!ownsRecorder(pid, cleanup = true), "Owned recorder did not stop")
            }
            recorderPid = null
            shell("remove owned capture", "rm -f ${proofShellQuote(remoteCapture)} ${proofShellQuote(remotePid)}", cleanup = true)
            recordingStarted = false
        }
        attempt { recorder?.let(::reapHostProcess) }
        recorder = null
        return clean
    }

    private fun shell(stage: String, script: String, allowFailure: Boolean = false, cleanup: Boolean = false): String =
        command(stage, listOf(adb, "-s", serial, "shell", script), allowFailure = allowFailure, cleanup = cleanup)

    private fun start(args: List<String>, output: File): Process =
        ProcessBuilder(args).directory(root).redirectErrorStream(true).redirectOutput(output).start()

    private fun command(stage: String, args: List<String>, cli: Boolean = false, allowFailure: Boolean = false, cleanup: Boolean = false): String {
        val timeout = if (cleanup) {
            minOf(3_000L, TimeUnit.NANOSECONDS.toMillis(cleanupDeadline - System.nanoTime()))
        } else remainingMillis()
        requireProof(timeout > 0, "$stage cleanup deadline expired")
        val process = ProcessBuilder(args).directory(root).redirectErrorStream(true).apply {
            if (cli) {
                environment().remove("PUTIO_CLI_TOKEN")
                environment()["PUTIO_CLI_PROFILE"] = "devs-auto"
            }
        }.start()
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (bytes.size() + size <= MAX_OUTPUT_BYTES) bytes.write(buffer, 0, size)
                    else process.destroyForcibly()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            requireProof(process.waitFor(timeout, TimeUnit.MILLISECONDS), "$stage timed out")
            reader.join(1000)
            requireProof(!reader.isAlive, "$stage output did not close")
            requireProof(allowFailure || process.exitValue() == 0, "$stage failed")
            return bytes.toString(Charsets.UTF_8.name())
        } finally {
            if (process.isAlive) reapHostProcess(process)
        }
    }

    private fun waitFor(process: Process, stage: String, output: File) {
        while (process.isAlive) {
            requireProof(output.length() <= MAX_OUTPUT_BYTES, "$stage output exceeded its limit")
            process.waitFor(minOf(remainingMillis(), 500L), TimeUnit.MILLISECONDS)
        }
        requireProof(process.exitValue() == 0, "$stage failed")
    }

    private fun reapHostProcess(process: Process) {
        val owned = process.descendants().use { it.toList().asReversed() } + process.toHandle()
        owned.forEach { it.destroyForcibly() }
        val reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        for (handle in owned) {
            if (handle.isAlive) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(reapDeadline - System.nanoTime())
                requireProof(remaining > 0, "Owned host process did not stop")
                handle.onExit().get(remaining, TimeUnit.MILLISECONDS)
            }
        }
        requireProof(owned.none { it.isAlive }, "Owned host process remained alive after cleanup")
    }

    private fun remainingMillis(): Long {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        requireProof(remaining > 0, "Authenticated proof exceeded 240 seconds")
        return remaining
    }
}

internal data class ProofManifest(val packageName: String, val targetPackage: String?, val runner: String?)

internal fun parseProofManifest(xml: String): ProofManifest {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
    val root = document.documentElement
    requireProof(root.tagName == "manifest", "APK manifest root missing")
    val packageName = root.getAttribute("package")
    val nodes = root.getElementsByTagName("instrumentation")
    requireProof(nodes.length <= 1, "APK runner is ambiguous")
    val node = nodes.item(0) as? org.w3c.dom.Element
    val namespace = "http://schemas.android.com/apk/res/android"
    val target = node?.getAttributeNS(namespace, "targetPackage")
    val runner = node?.getAttributeNS(namespace, "name")?.let { if (it.startsWith('.')) packageName + it else it }
    requireProof(listOfNotNull(packageName, target, runner).all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) }, "Invalid APK identity")
    return ProofManifest(packageName, target, runner)
}

internal fun validateRenameFixture(fixture: JsonObject) {
    val ids = setOf("expectedAccountId", "containerId", "renameItemId", "cancelItemId")
    val names = setOf("containerName", "renameOriginalName", "renameNewName", "cancelOriginalName")
    requireProof(fixture.keys == ids + names, "Fixture must contain exactly the eight documented fields")
    ids.forEach { proofId(fixture, it) }
    names.forEach { requireProof(proofText(fixture, it).isNotEmpty(), "Fixture names must be nonempty") }
    requireProof(listOf("containerId", "renameItemId", "cancelItemId").map { proofId(fixture, it) }.distinct().size == 3, "Fixture IDs must be distinct")
    requireProof(listOf("renameOriginalName", "renameNewName", "cancelOriginalName").map { proofText(fixture, it) }.distinct().size == 3, "Fixture names must be distinct")
}

private fun parseProofObject(raw: String, label: String): JsonObject = try {
    Json.parseToJsonElement(raw) as? JsonObject ?: throw GradleException("$label is not an object")
} catch (_: IllegalArgumentException) {
    throw GradleException("Invalid $label JSON")
}

private fun proofId(value: JsonObject, key: String): Long {
    val number = value[key] as? JsonPrimitive
    requireProof(number != null && !number.isString && number.content.matches(Regex("[1-9][0-9]*")), "Invalid numeric field $key")
    return number?.longOrNull?.takeIf { it > 0 } ?: throw GradleException("Invalid numeric field $key")
}

private fun proofText(value: JsonObject, key: String): String {
    val text = value[key] as? JsonPrimitive
    requireProof(text != null && text.isString, "Invalid string field $key")
    return requireNotNull(text).content
}

internal fun proofShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
private fun requireProof(condition: Boolean, message: String) {
    if (!condition) throw GradleException(message)
}
private const val MAX_OUTPUT_BYTES = 1_048_576
private const val PROOF_CLASS = "io.putdotio.android.AuthenticatedFilesRenameTest"
private const val PROOF_METHOD = "authenticatedRenamePreservesSessionAndCancel"

internal fun activeProofInstrumentation(output: String): Set<String> {
    requireProof(output.contains("ACTIVITY MANAGER RUNNING PROCESSES"), "Invalid activity process dump")
    val entries = Regex("mClass=ComponentInfo\\{([^}]+)} mFinished=(?:true|false)").findAll(output).map {
        val component = it.groupValues[1]
        val packageName = component.substringBefore('/')
        val className = component.substringAfter('/')
        "$packageName/${if (className.startsWith('.')) packageName + className else className}"
    }.toSet()
    requireProof(!output.contains("Active instrumentation:") || entries.isNotEmpty(), "Unparseable active instrumentation")
    return entries
}
