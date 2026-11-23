import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncPlayBundlesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun returnsOneBundlePerApp() {
        val mobile = temporaryFolder.newFile("mobile-production-release.aab")
        val tv = temporaryFolder.newFile("tv-production-release.aab")
        assertEquals(listOf(mobile, tv), requirePlayBundles(mapOf("mobile" to setOf(mobile), "TV" to setOf(tv))))
    }

    @Test
    fun rejectsMissingTvBundle() {
        val mobile = temporaryFolder.newFile("mobile-production-release.aab")
        val missing = temporaryFolder.root.resolve("tv-production-release.aab")
        val error = assertThrows(GradleException::class.java) {
            requirePlayBundles(mapOf("mobile" to setOf(mobile), "TV" to setOf(missing)))
        }
        assertEquals(true, error.message.orEmpty().contains("one TV .aab, found 0"))
    }
}
