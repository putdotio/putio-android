import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAuthenticatedRenameProofTest {
    @Test
    fun readsActiveInstrumentationComponentsAndExpandsRelativeRunnerNames() {
        val header = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
        assertEquals(emptySet<String>(), activeProofInstrumentation(header))
        assertEquals(setOf("io.put.test/io.put.test.Runner"), activeProofInstrumentation(
            header + "  Active instrumentation:\n    mClass=ComponentInfo{io.put.test/.Runner} mFinished=false\n",
        ))
        assertEquals(setOf("io.put.test/Runner", "other.test/OtherRunner"), activeProofInstrumentation(
            header + "  Active instrumentation:\n" +
                "    mClass=ComponentInfo{io.put.test/Runner} mFinished=false\n" +
                "    mClass=ComponentInfo{other.test/OtherRunner} mFinished=false\n",
        ))
    }

    @Test
    fun rejectsUnsupportedOrUnparseableProcessDumps() {
        for (output in listOf("", "Unknown command: instrumentation", "Active instrumentation:\n",
            "ACTIVITY MANAGER RUNNING PROCESSES\n  Active instrumentation:\n    unknown-format")) {
            assertThrows(GradleException::class.java) { activeProofInstrumentation(output) }
        }
    }

    @Test
    fun readsAppPackageAndNamespacedInstrumentationIdentity() {
        assertEquals(ProofManifest("io.put.putio.mobile.debug", null, null), parseProofManifest(manifest()))
        assertEquals(
            ProofManifest("io.put.putio.mobile.debug", "io.put.putio.mobile.debug", "androidx.test.runner.AndroidJUnitRunner"),
            parseProofManifest(manifest(instrumentation())),
        )
        assertEquals("io.put.putio.mobile.debug.Runner", parseProofManifest(manifest(instrumentation(".Runner"))).runner)
    }

    @Test
    fun rejectsMalformedMissingAndAmbiguousManifestIdentity() {
        for (xml in listOf(
            "<manifest", "<application package=\"io.put.putio\"/>",
            "<manifest/>", manifest().replace("io.put.putio.mobile.debug", "bad package; command"),
            manifest(instrumentation() + instrumentation()),
            manifest("<instrumentation/>"),
            manifest("<instrumentation name=\"Runner\" targetPackage=\"io.put.putio.mobile.debug\"/>"),
        )) {
            assertThrows(Exception::class.java) { parseProofManifest(xml) }
        }
    }

    @Test
    fun rejectsExternalEntityManifestBeforeResolvingIt() {
        val xml = """
            <!DOCTYPE manifest [<!ENTITY external SYSTEM "file:///putio-proof-must-not-read">]>
            <manifest package="io.put.putio.mobile.debug"><application>&external;</application></manifest>
        """.trimIndent()
        assertThrows(Exception::class.java) { parseProofManifest(xml) }
    }

    @Test
    fun acceptsExactUnicodeNamesAndLongIdsWithoutNormalizingInput() {
        val fixture = fixture()
        val before = fixture.toString()
        validateRenameFixture(fixture)
        assertEquals(before, fixture.toString())
        validateRenameFixture(replace("renameItemId", JsonPrimitive(Long.MAX_VALUE)))
    }

    @Test
    fun requiresExactlyTheDocumentedFields() {
        rejectFixture(JsonObject(fixture() - "containerName"))
        rejectFixture(JsonObject(fixture() + ("extra" to JsonPrimitive("unexpected"))))
    }

    @Test
    fun rejectsNonIntegralNonPositiveOverflowAndStringIds() {
        val invalid = listOf("0", "-1", "1.5", "1e3", "9223372036854775808", "true", "null", "[]", "{}", "\"123\"")
        for (key in listOf("expectedAccountId", "containerId", "renameItemId", "cancelItemId")) {
            for (raw in invalid) rejectFixture(replace(key, Json.parseToJsonElement(raw)))
        }
    }

    @Test
    fun rejectsEmptyOrNonStringNames() {
        for (key in listOf("containerName", "renameOriginalName", "renameNewName", "cancelOriginalName")) {
            for (value in listOf(JsonPrimitive(""), JsonPrimitive(123), JsonPrimitive(true), JsonNull)) {
                rejectFixture(replace(key, value))
            }
        }
    }

    @Test
    fun requiresSpaceAndNonAsciiInBothRenameNames() {
        for (key in listOf("renameOriginalName", "renameNewName")) {
            for (name in listOf("ASCII name.txt", "東京.txt", "été\tfile.txt")) {
                rejectFixture(replace(key, JsonPrimitive(name)))
            }
        }
    }

    @Test
    fun acceptsOnlyTheNeededCliCapabilities() {
        val contract = cliContract()
        validateRenameCliContract(contract)
        val commands = contract.getValue("commands") as JsonArray
        assertThrows(GradleException::class.java) {
            validateRenameCliContract(JsonObject(contract + ("commands" to JsonArray(commands.dropLast(1)))))
        }
        for (replacement in listOf(
            JsonObject(mapOf("kind" to JsonPrimitive("write"))),
            JsonObject(mapOf("auth" to JsonObject(mapOf("required" to JsonPrimitive(false))))),
            JsonObject(mapOf("capabilities" to JsonObject(mapOf("fieldSelection" to JsonPrimitive(false))))),
            JsonObject(mapOf("input" to JsonObject(mapOf("flags" to JsonArray(emptyList()))))),
        )) {
            rejectCliCommand(JsonObject((commands.last() as JsonObject) + replacement))
        }
    }

    @Test
    fun rejectsIncompatibleCliFlagSchemasBeforeUsingCommands() {
        val command = (cliContract().getValue("commands") as JsonArray).last() as JsonObject
        val input = command.getValue("input") as JsonObject
        val flags = input.getValue("flags") as JsonArray
        for ((flagName, changes) in listOf(
            "parent-id" to mapOf("type" to JsonPrimitive("string")),
            "per-page" to mapOf("repeated" to JsonPrimitive(true)),
            "output" to mapOf("choices" to JsonArray(listOf(JsonPrimitive("text")))),
        )) {
            val changed = flags.map { value ->
                val flag = value as JsonObject
                if (flag["name"] == JsonPrimitive(flagName)) JsonObject(flag + changes) else flag
            }
            rejectCliCommand(JsonObject(command + ("input" to JsonObject(input + ("flags" to JsonArray(changed))))))
        }
        val required = JsonObject(mapOf("name" to JsonPrimitive("new-flag"), "required" to JsonPrimitive(true)))
        rejectCliCommand(JsonObject(command + ("input" to JsonObject(input + ("flags" to JsonArray(flags + required))))))
    }

    private fun cliContract() = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/rename-proof-cli-contract.json")).readText(),
    ) as JsonObject

    private fun rejectCliCommand(command: JsonObject) {
        val contract = cliContract()
        val commands = contract.getValue("commands") as JsonArray
        val error = assertThrows(GradleException::class.java) {
            validateRenameCliContract(JsonObject(contract + ("commands" to JsonArray(commands.dropLast(1) + command))))
        }
        assertTrue(error.message.orEmpty().contains("putio CLI contract missing or incompatible"))
    }

    @Test
    fun rejectsReusedItemIdsAndConflictingNames() {
        rejectFixture(replace("renameItemId", fixture().getValue("containerId")))
        rejectFixture(replace("cancelItemId", fixture().getValue("renameItemId")))
        rejectFixture(replace("renameNewName", fixture().getValue("renameOriginalName")))
        rejectFixture(replace("cancelOriginalName", fixture().getValue("renameNewName")))
    }

    @Test
    fun shellQuotePreservesOneLiteralArgumentWithoutExpandingShellSyntax() {
        for (value in listOf("", "plain", "Bodrum été 東京", "a'b\"c", "line one\nline two",
            "\$(printf expanded); `printf expanded` \$HOME * ? [abc] \\ --")) {
            val command = "set -- ${proofShellQuote(value)}; [ \"\$#\" -eq 1 ] && printf '%s' \"\$1\""
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            try {
                assertTrue("Quoted argument command timed out", process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                assertEquals(value, process.inputStream.bufferedReader().readText())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun manifest(content: String = "") =
        "<manifest xmlns:a=\"http://schemas.android.com/apk/res/android\" package=\"io.put.putio.mobile.debug\">$content</manifest>"

    private fun instrumentation(runner: String = "androidx.test.runner.AndroidJUnitRunner") =
        "<instrumentation a:name=\"$runner\" a:targetPackage=\"io.put.putio.mobile.debug\"/>"

    private fun fixture() = JsonObject(mapOf(
        "expectedAccountId" to JsonPrimitive(11),
        "containerId" to JsonPrimitive(12),
        "renameItemId" to JsonPrimitive(13),
        "cancelItemId" to JsonPrimitive(14),
        "containerName" to JsonPrimitive("Owned proof container"),
        "renameOriginalName" to JsonPrimitive("  Bodrum été 東京 e\u0301.txt  "),
        "renameNewName" to JsonPrimitive("  Bodrum été 東京 é.txt  "),
        "cancelOriginalName" to JsonPrimitive("Keep 'quoted' filename.txt"),
    ))

    private fun replace(key: String, value: JsonElement) = JsonObject(fixture() + (key to value))

    private fun rejectFixture(value: JsonObject) {
        assertThrows(GradleException::class.java) { validateRenameFixture(value) }
    }
}
