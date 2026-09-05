import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    private var cleanupStarted = false
    private var activeCommand: Process? = null
    private val cleanupFailures = mutableListOf<Exception>()

    fun run(fixtureFile: File, app: File, test: File) {
        val cleanupHook = Thread({
            if (!cleanup()) report("CLEANUP FAIL Authenticated proof shutdown cleanup failed; inspect owned run $runId")
        }, "putio-rename-cleanup-$runId")
        Runtime.getRuntime().addShutdownHook(cleanupHook)
        var failure: Throwable? = null
        var validatedEvidence: String? = null
        try {
            val raw = fixtureFile.inputStream().use { it.readNBytes(24_577) }
            requireProof(raw.size <= 24_576, "Fixture exceeds proof input limit")
            val fixture = Json.decodeFromString(RenameFixtureDecoder, raw.decodeToString(throwOnInvalidSequence = true))
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
                requireProof(!cleanupStarted, "Authenticated proof cleanup has started")
                instrumentation = start(listOf(adb, "-s", serial, "shell", args.joinToString(" ", transform = ::proofShellQuote)), output)
                instrumentationStarted = true
            }
            val running = requireNotNull(instrumentation)
            waitFor(running, "instrumentation", output)
            instrumentation = null
            val resultBytes = output.inputStream().use { it.readNBytes(MAX_OUTPUT_BYTES + 1) }
            requireProof(resultBytes.size <= MAX_OUTPUT_BYTES, "Instrumentation output exceeded its limit")
            val result = resultBytes.decodeToString(throwOnInvalidSequence = true)
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
                cleanupFailures.forEach(cleanupFailure::addSuppressed)
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
        validateRenameCliContract(cli("describe", "--output", "json"))
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
            requireProof(!cleanupStarted, "Authenticated proof cleanup has started")
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
        cleanupStarted = true
        val interrupted = Thread.interrupted()
        cleanupFailures.clear()
        cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        return try {
            cleanupOwnedProcesses()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun cleanupOwnedProcesses(): Boolean {
        var clean = true
        fun attempt(block: () -> Unit) {
            try { block() } catch (error: Exception) { cleanupFailures += error; clean = false }
        }
        attempt {
            activeCommand?.let(::reapHostProcess)
            activeCommand = null
        }
        if (!::adb.isInitialized) return clean
        if (instrumentationStarted) attempt {
            val active = try {
                activeInstrumentation(cleanup = true)
            } catch (error: Exception) {
                report("CLEANUP FAIL Instrumentation ownership check failed; active instrumentation was not signaled")
                throw error
            }
            if (active.isNotEmpty()) {
                // The same runner can belong to a replacement invocation. API 37
                // dumps keep arguments parcelled, so no per-run identity is visible.
                val message = "Active instrumentation preserved: run ownership cannot be established"
                report("CLEANUP FAIL $message")
                throw GradleException(message)
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
        val process = synchronized(this) {
            requireProof(cleanup || !cleanupStarted, "Authenticated proof cleanup has started")
            ProcessBuilder(args).directory(root).redirectErrorStream(true).apply {
                if (cli) {
                    environment().remove("PUTIO_CLI_TOKEN")
                    environment()["PUTIO_CLI_PROFILE"] = "devs-auto"
                }
            }.start().also {
                // Cleanup commands already run under cleanup's monitor. Keep the
                // foreground command reachable if the JVM skips its finally block.
                if (!cleanup) activeCommand = it
            }
        }
        val bytes = ByteArrayOutputStream()
        var outputOverflow = false
        var readerFailure: Exception? = null
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (bytes.size() + size > MAX_OUTPUT_BYTES) {
                            outputOverflow = true
                            // Snapshot descendants while their launcher is still alive.
                            reapHostProcess(process)
                            break
                        }
                        bytes.write(buffer, 0, size)
                    }
                }
            } catch (error: Exception) {
                readerFailure = error
            }
        }.apply { isDaemon = true; start() }
        try {
            requireProof(process.waitFor(timeout, TimeUnit.MILLISECONDS), "$stage timed out")
            // Overflow cleanup includes the existing two-second owned-process reap.
            val commandDeadline = if (cleanup) cleanupDeadline else deadline
            val readerWait = minOf(3_000L, TimeUnit.NANOSECONDS.toMillis(commandDeadline - System.nanoTime()))
            if (readerWait > 0) reader.join(readerWait)
            requireProof(System.nanoTime() < commandDeadline, "$stage timed out")
            requireProof(!reader.isAlive, "$stage output did not close")
            readerFailure?.let { throw GradleException("$stage output cleanup failed", it) }
            requireProof(!outputOverflow, "$stage output exceeded its limit")
            requireProof(allowFailure || process.exitValue() == 0, "$stage failed")
            return bytes.toString(Charsets.UTF_8.name())
        } finally {
            try {
                if (process.isAlive) reapHostProcess(process)
            } finally {
                synchronized(this) {
                    if (activeCommand === process) activeCommand = null
                }
            }
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
    for (key in listOf("renameOriginalName", "renameNewName")) {
        val name = proofText(fixture, key)
        requireProof(' ' in name && name.any { it.code > 127 }, "$key must contain a space and a non-ASCII character")
    }
    requireProof(listOf("containerId", "renameItemId", "cancelItemId").map { proofId(fixture, it) }.distinct().size == 3, "Fixture IDs must be distinct")
    requireProof(listOf("renameOriginalName", "renameNewName", "cancelOriginalName").map { proofText(fixture, it) }.distinct().size == 3, "Fixture names must be distinct")
}

internal fun validateRenameCliContract(contract: JsonObject) {
    fun requireCapability(condition: Boolean, detail: String) =
        requireProof(condition, "putio CLI contract missing or incompatible: $detail; install a CLI supporting the rename proof")
    requireCapability((contract["auth"] as? JsonObject)?.get("profileEnv") == JsonPrimitive("PUTIO_CLI_PROFILE"),
        "PUTIO_CLI_PROFILE selection")
    val commands = contract["commands"] as? JsonArray
    for (name in listOf("auth status", "whoami", "files list")) {
        val command = commands?.filterIsInstance<JsonObject>()?.singleOrNull { it["command"] == JsonPrimitive(name) }
        requireCapability(command != null, "$name command")
        val selected = requireNotNull(command)
        val isRead = name != "auth status"
        requireCapability(selected["kind"] == JsonPrimitive(if (isRead) "read" else "auth") &&
            (selected["auth"] as? JsonObject)?.get("required") == JsonPrimitive(isRead), "$name operation and auth")
        if (isRead) requireCapability((selected["capabilities"] as? JsonObject)?.get("fieldSelection") == JsonPrimitive(true),
            "$name field selection")
        val requiredFlags = when (name) {
            "auth status" -> mapOf("output" to "enum", "profile" to "string")
            "whoami" -> mapOf("output" to "enum", "fields" to "string")
            else -> mapOf("output" to "enum", "fields" to "string", "parent-id" to "integer", "per-page" to "integer")
        }
        val input = selected["input"] as? JsonObject
        val flags = (input?.get("flags") as? JsonArray)?.filterIsInstance<JsonObject>()
        requireCapability(flags != null, "$name input flags")
        for ((flagName, type) in requiredFlags) {
            val flag = flags?.singleOrNull { it["name"] == JsonPrimitive(flagName) }
            requireCapability(flag?.get("type") == JsonPrimitive(type) && flag?.get("repeated") == JsonPrimitive(false),
                "$name --$flagName $type flag")
            if (flagName == "output") requireCapability((flag?.get("choices") as? JsonArray)?.contains(JsonPrimitive("json")) == true,
                "$name --output json")
        }
        requireCapability(flags.orEmpty().none {
            it["required"] == JsonPrimitive(true) && (it["name"] as? JsonPrimitive)?.content !in requiredFlags
        }, "$name has an unsupported required flag")
        requireCapability((input?.get("arguments") as? JsonArray).orEmpty().none {
            (it as? JsonObject)?.get("required") == JsonPrimitive(true)
        }, "$name has an unsupported required argument")
    }
}

// JsonObject parsing collapses duplicate keys. Decode fixture entries before
// building the object so host preflight rejects the same input as the device.
internal object RenameFixtureDecoder : DeserializationStrategy<JsonObject> {
    override val descriptor = MapSerializer(String.serializer(), JsonElement.serializer()).descriptor

    override fun deserialize(decoder: Decoder): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                val key = decodeStringElement(descriptor, index)
                requireProof(key !in fields, "Duplicate fixture field")
                val valueIndex = decodeElementIndex(descriptor)
                requireProof(valueIndex == index + 1, "Invalid fixture entry")
                fields[key] = decodeSerializableElement(descriptor, valueIndex, JsonElement.serializer())
            }
        }
        return JsonObject(fields)
    }
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
