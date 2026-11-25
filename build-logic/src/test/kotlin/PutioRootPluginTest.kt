import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PutioRootPluginTest {
    @Test
    fun rejectsSubprojectApplication() {
        val root = ProjectBuilder.builder().withName("root").build()
        val child = ProjectBuilder.builder().withName("child").withParent(root).build()
        val error = assertThrows(GradleException::class.java) {
            child.pluginManager.apply("putio.root")
        }
        val cause = generateSequence<Throwable>(error) { it.cause }
            .firstOrNull { it is IllegalArgumentException }
        assertTrue(
            "Expected IllegalArgumentException mentioning root project, got ${error.stackTraceToString()}",
            cause?.message?.contains("putio.root must be applied to the root project") == true,
        )
    }

    @Test
    fun registersExpectedLanesAndGuardrailsOnRootProject() {
        val root = ProjectBuilder.builder().withName("root").build()
        root.pluginManager.apply("putio.root")

        val expected = listOf(
            "verify",
            "verifyDocs",
            "verifyScripts",
            "verifyLibraries",
            "verifyMobile",
            "verifyTv",
            "verifyRelease",
            "markdownCheck",
            "lintDesignMd",
            "checkOkHttpClient",
            "checkShellScripts",
        )
        for (name in expected) {
            assertTrue("Missing registered task $name", name in root.tasks.names)
        }
    }
}
