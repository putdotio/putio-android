import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyR8KeepTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsSeedsContainingRequiredClassesAndFields() {
        val seeds = temporaryFolder.newFile("seeds.txt").apply {
            writeText(
                """
                com.posthog.PostHogEvent
                com.posthog.PostHogEvent: java.lang.String event
                com.posthog.internal.PostHogBatchEvent
                com.posthog.internal.PostHogBatchEvent: java.util.List batch
                """.trimIndent(),
            )
        }
        requireR8KeptClassesWithFields(seeds, REQUIRED)
    }

    @Test
    fun rejectsMissingRequiredClass() {
        val seeds = temporaryFolder.newFile("seeds.txt").apply {
            writeText(
                """
                com.posthog.PostHogEvent
                com.posthog.PostHogEvent: java.lang.String event
                """.trimIndent(),
            )
        }
        assertThrows(GradleException::class.java) {
            requireR8KeptClassesWithFields(seeds, REQUIRED)
        }
    }

    @Test
    fun rejectsClassKeptWithoutAnyFields() {
        val seeds = temporaryFolder.newFile("seeds.txt").apply {
            writeText(
                """
                com.posthog.PostHogEvent
                com.posthog.PostHogEvent: java.lang.String toString()
                com.posthog.internal.PostHogBatchEvent
                com.posthog.internal.PostHogBatchEvent: java.util.List batch
                """.trimIndent(),
            )
        }
        assertThrows(GradleException::class.java) {
            requireR8KeptClassesWithFields(seeds, REQUIRED)
        }
    }

    @Test
    fun rejectsMissingSeedsFile() {
        assertThrows(GradleException::class.java) {
            requireR8KeptClassesWithFields(temporaryFolder.root.resolve("missing-seeds.txt"), REQUIRED)
        }
    }

    private companion object {
        val REQUIRED = listOf("com.posthog.PostHogEvent", "com.posthog.internal.PostHogBatchEvent")
    }
}
