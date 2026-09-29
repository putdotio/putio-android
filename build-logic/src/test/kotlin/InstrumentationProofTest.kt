import org.gradle.api.GradleException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class InstrumentationProofTest {
    private val className = "io.putdotio.android.AuthenticatedFilesRenameTest"
    private val testName = "renamesOwnedFixture"

    @Test
    fun acceptsOneNamedTestWithRawRunnerMetadataAndMultilineStream() {
        verify(success())
        verify(success().replace("\n", "\r\n"))
    }

    @Test
    fun rejectsMissingZeroAndFreeFormSuccess() {
        listOf("", "OK (1 test)", "INSTRUMENTATION_RESULT: stream=\nOK (0 tests)\n$terminal")
            .forEach(::reject)
    }

    @Test
    fun rejectsSkippedFailedAndErroredTests() {
        for (code in listOf(-4, -3, -2, -1)) {
            reject(block(1) + block(code) + terminal)
        }
    }

    @Test
    fun rejectsOtherClassOrMethodEvenWhenSuccessful() {
        reject(success().replace(className, "OtherTest"))
        reject(success().replace(testName, "otherMethod"))
        reject(block(1) + block(0).replace(testName, "otherMethod") + terminal)
    }

    @Test
    fun rejectsMissingStartSuccessOrIdentity() {
        reject(block(0) + terminal)
        reject(block(1) + terminal)
        reject(success().replace("INSTRUMENTATION_STATUS: test=$testName\n", ""))
        reject(block(0) + block(1) + terminal)
    }

    @Test
    fun rejectsDuplicateTestsStatusesFieldsAndTerminalResults() {
        reject(block(1) + block(1) + block(0) + terminal)
        reject(block(1) + block(0) + block(0) + terminal)
        reject(block(1) + block(0) + block(1) + block(0) + terminal)
        reject(success() + block(1))
        reject(success() + terminal)
        reject("INSTRUMENTATION_STATUS: test=$testName\n" + success())
    }

    @Test
    fun rejectsTruncatedMalformedAndUnsuccessfulTerminalResults() {
        reject(block(1) + block(0))
        reject(block(1) + "INSTRUMENTATION_STATUS: class=$className\n" + terminal)
        reject(success().replace(terminal, "INSTRUMENTATION_CODE: 0\n"))
        reject(success().replace(terminal, "INSTRUMENTATION_CODE: invalid\n"))
        reject(success().replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: invalid"))
        reject(success().replace("class=$className", "class"))
    }

    @Test
    fun rejectsRunnerErrorsWithoutEchoingSensitivePayload() {
        for (error in listOf("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:",
            "INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_RESULT: longMsg=")) {
            val failure = reject(block(1) + block(0) + error + "sensitive-payload\n" + terminal)
            assertFalse(failure.message.orEmpty().contains("sensitive-payload"))
        }
    }

    @Test
    fun rejectsMultipleTestsOrMalformedTestCounts() {
        reject(success().replace("numtests=1", "numtests=2"))
        reject(success().replace("current=1", "current=2"))
        reject(success().replace("numtests=1", "numtests=invalid"))
    }

    private fun success() = block(1) + block(0) +
        "INSTRUMENTATION_RESULT: stream=\nTime: 1.5\n\nOK (1 test)\n" + terminal

    private fun block(code: Int) = """
        INSTRUMENTATION_STATUS: class=$className
        INSTRUMENTATION_STATUS: current=1
        INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
        INSTRUMENTATION_STATUS: numtests=1
        INSTRUMENTATION_STATUS: stream=
        $className:
        INSTRUMENTATION_STATUS: test=$testName
        INSTRUMENTATION_STATUS_CODE: $code

    """.trimIndent()

    private fun verify(output: String) = requireSuccessfulInstrumentation(output, className, testName)

    private fun reject(output: String): GradleException =
        assertThrows(GradleException::class.java) { verify(output) }

    private val terminal = "INSTRUMENTATION_CODE: -1\n"
}
