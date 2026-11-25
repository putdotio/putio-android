import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckForbiddenSourceTaskTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun catchesDirectMultilineReferenceAndAliasBypassAttemptsWithLineNumbers() {
        val root = temp.newFolder()
        val sample = root.resolve("mobile/src/debug/kotlin/BypassSamples.kt").apply { parentFile.mkdirs() }
        sample.writeText(
            """
            import okhttp3.OkHttpClient
            import okhttp3.OkHttpClient.*
            import okhttp3.OkHttpClient as Http

            val direct = OkHttpClient()
            val multiline = OkHttpClient
                .Builder()
                .build()
            val ctorRef = ::OkHttpClient
            val builderRef = OkHttpClient::Builder
            """.trimIndent() + "\n",
        )

        val error = runTask(root, sample)
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("mobile/src/debug/kotlin/BypassSamples.kt:2"))
        assertTrue(message, message.contains("mobile/src/debug/kotlin/BypassSamples.kt:3"))
        assertTrue(message, message.contains("mobile/src/debug/kotlin/BypassSamples.kt:5"))
        assertTrue(message, message.contains("mobile/src/debug/kotlin/BypassSamples.kt:6"))
        assertTrue(message, message.contains("mobile/src/debug/kotlin/BypassSamples.kt:9"))
        assertTrue(message, message.contains("mobile/src/debug/kotlin/BypassSamples.kt:10"))
        assertFalse(message, message.contains(":1\n") || message.endsWith(":1"))
    }

    @Test
    fun allowsClassLiteralAndPutioHttpClient() {
        val root = temp.newFolder()
        val allowed = root.resolve("mobile/src/main/kotlin/Allowed.kt").apply { parentFile.mkdirs() }
        allowed.writeText(
            """
            import okhttp3.OkHttpClient

            val klass = OkHttpClient::class
            val derived = PutioHttpClient.newBuilder().build()
            """.trimIndent() + "\n",
        )

        runCheck(root, allowed)
        assertTrue(root.resolve("build/check.stamp").isFile)
    }

    private fun runTask(root: java.io.File, file: java.io.File): GradleException =
        assertThrows(GradleException::class.java) { runCheck(root, file) }

    private fun runCheck(root: java.io.File, file: java.io.File) {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("checkOkHttp", CheckForbiddenSourceTask::class.java).get()
        task.sources.from(file)
        task.pattern.set(OKHTTP_CLIENT_PATTERN)
        task.reason.set("Forbidden OkHttpClient construction")
        task.repositoryRoot.set(root)
        val stamp = root.resolve("build/check.stamp").apply { parentFile.mkdirs() }
        task.stamp.set(stamp)
        task.check()
    }
}
