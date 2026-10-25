import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyDebugOnlyActivityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun releaseManifestDeclaringTheActivityFails() {
        writeManifest(ACTIVITY)
        assertThrows(GradleException::class.java) { verify(present = false) }
        verify(present = true)
    }

    @Test
    fun debugManifestMissingTheActivityFails() {
        writeManifest("io.putdotio.android.MainActivity")
        assertThrows(GradleException::class.java) { verify(present = true) }
        verify(present = false)
    }

    private fun verify(present: Boolean) =
        requireActivityPresence(temporaryFolder.root.resolve("AndroidManifest.xml"), ACTIVITY, present)

    private fun writeManifest(activity: String) {
        temporaryFolder.root.resolve("AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name="$activity" android:exported="true" />
                </application>
            </manifest>
            """.trimIndent(),
        )
    }

    private companion object {
        const val ACTIVITY = "io.putdotio.android.auth.DebugDeviceSignInActivity"
    }
}
