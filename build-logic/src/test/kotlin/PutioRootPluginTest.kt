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
}
