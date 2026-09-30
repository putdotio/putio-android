import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyLaunchProofTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsTheSuccessfulNamedSmokeTest() {
        writeReport(testCase())
        requireSuccessfulLaunchProof(temporaryFolder.root)
    }

    @Test
    fun rejectsMissingReportsAndEmptySuites() {
        assertThrows(GradleException::class.java) { requireSuccessfulLaunchProof(temporaryFolder.root) }
        writeReport("")
        assertThrows(GradleException::class.java) { requireSuccessfulLaunchProof(temporaryFolder.root) }
    }

    @Test
    fun otherSuccessfulTestsDoNotProveLaunch() {
        writeReport("<testcase classname=\"OtherTest\" name=\"passes\"/>")
        assertThrows(GradleException::class.java) { requireSuccessfulLaunchProof(temporaryFolder.root) }
    }

    @Test
    fun rejectsFailedErroredAndSkippedSmokeTests() {
        for (result in listOf("failure", "error", "skipped")) {
            writeReport(testCase("<$result/>"))
            assertThrows(GradleException::class.java) { requireSuccessfulLaunchProof(temporaryFolder.root) }
        }
    }

    private fun writeReport(content: String) {
        temporaryFolder.root.resolve("TEST-launch.xml").writeText("<testsuite>$content</testsuite>")
    }

    private fun testCase(content: String = "") =
        "<testcase classname=\"io.putdotio.android.LaunchSmokeTest\" " +
            "name=\"shellLaunchesStaysResumedAndRenders\">$content</testcase>"
}
