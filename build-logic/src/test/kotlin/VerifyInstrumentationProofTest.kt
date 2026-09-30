import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyInstrumentationProofTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsTheSuccessfulNamedTest() {
        writeReport(testCase())
        verify()
    }

    @Test
    fun rejectsMissingReportsAndEmptySuites() {
        assertThrows(GradleException::class.java) { verify() }
        writeReport("")
        assertThrows(GradleException::class.java) { verify() }
    }

    @Test
    fun otherSuccessfulTestsDoNotProveTheRequiredTest() {
        writeReport(
            "<testcase classname=\"OtherTest\" name=\"passes\"/>" +
                "<testcase classname=\"$CLASS\" name=\"otherMethod\"/>",
        )
        assertThrows(GradleException::class.java) { verify() }
    }

    @Test
    fun requiredTestSelectsWhichResultCounts() {
        val oauthTest = "io.putdotio.android.auth.StaleOAuthCallbackTest#staleCallback"
        writeReport(testCase())
        assertThrows(GradleException::class.java) {
            requireSuccessfulInstrumentationResult(temporaryFolder.root, oauthTest)
        }
        writeReport("<testcase classname=\"io.putdotio.android.auth.StaleOAuthCallbackTest\" name=\"staleCallback\"/>")
        requireSuccessfulInstrumentationResult(temporaryFolder.root, oauthTest)
    }

    @Test
    fun rejectsFailedErroredAndSkippedResults() {
        for (result in listOf("failure", "error", "skipped")) {
            writeReport(testCase("<$result/>"))
            assertThrows(GradleException::class.java) { verify() }
        }
    }

    @Test
    fun rejectsRequiredTestWithoutMethod() {
        writeReport(testCase())
        assertThrows(IllegalArgumentException::class.java) {
            requireSuccessfulInstrumentationResult(temporaryFolder.root, CLASS)
        }
    }

    private fun verify() = requireSuccessfulInstrumentationResult(temporaryFolder.root, "$CLASS#$METHOD")

    private fun writeReport(content: String) {
        temporaryFolder.root.resolve("TEST-launch.xml").writeText("<testsuite>$content</testsuite>")
    }

    private fun testCase(content: String = "") =
        "<testcase classname=\"$CLASS\" name=\"$METHOD\">$content</testcase>"

    private companion object {
        const val CLASS = "io.putdotio.android.LaunchSmokeTest"
        const val METHOD = "shellLaunchesStaysResumedAndRenders"
    }
}
