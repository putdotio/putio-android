import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyLauncherManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsMainWithEachLauncherCategory() {
        writeManifest(filter(MAIN, LEANBACK) + filter(MAIN, LAUNCHER))
        verify(LAUNCHER, LEANBACK)
    }

    @Test
    fun acceptsOneFilterCarryingEveryCategory() {
        writeManifest(filter(MAIN, LAUNCHER, LEANBACK))
        verify(LAUNCHER, LEANBACK)
    }

    @Test
    fun rejectsLeanbackFilterWithoutMainAction() {
        // The merger keeps a flavor filter apart from main's {MAIN, LAUNCHER}; without its own
        // MAIN action the TV home query never matches it.
        writeManifest(filter(LEANBACK) + filter(MAIN, LAUNCHER))
        verify(LAUNCHER)
        assertThrows(GradleException::class.java) { verify(LAUNCHER, LEANBACK) }
    }

    @Test
    fun rejectsCategoryOnAnotherActivity() {
        writeManifest(filter(MAIN, LAUNCHER), otherActivity = filter(MAIN, LEANBACK))
        assertThrows(GradleException::class.java) { verify(LEANBACK) }
    }

    @Test
    fun rejectsFilterThatRequiresData() {
        writeManifest(filter(MAIN, LEANBACK, data = "<data android:scheme=\"https\"/>"))
        assertThrows(GradleException::class.java) { verify(LEANBACK) }
    }

    @Test
    fun rejectsMissingActivity() {
        writeManifest("", otherActivity = filter(MAIN, LAUNCHER))
        assertThrows(GradleException::class.java) { verify(LAUNCHER) }
    }

    private fun verify(vararg categories: String) =
        requireLauncherEntries(temporaryFolder.root.resolve("AndroidManifest.xml"), ACTIVITY, categories.toList())

    private fun writeManifest(filters: String, otherActivity: String = "") {
        temporaryFolder.root.resolve("AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name="$ACTIVITY" android:exported="true">$filters</activity>
                    <activity android:name="io.putdotio.android.OtherActivity" android:exported="true">$otherActivity</activity>
                </application>
            </manifest>
            """.trimIndent(),
        )
    }

    private fun filter(vararg names: String, data: String = "") =
        "<intent-filter>" +
            names.joinToString("") { name ->
                val tag = if (name == MAIN) "action" else "category"
                "<$tag android:name=\"$name\"/>"
            } +
            data + "</intent-filter>"

    private companion object {
        const val ACTIVITY = "io.putdotio.android.MainActivity"
        const val MAIN = "android.intent.action.MAIN"
        const val LAUNCHER = "android.intent.category.LAUNCHER"
        const val LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"
    }
}
