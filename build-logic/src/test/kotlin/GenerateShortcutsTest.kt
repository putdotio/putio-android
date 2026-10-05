import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GenerateShortcutsTest {
    @Test
    fun everyPlaceholderBecomesTheVariantsApplicationId() {
        val template = """<intent android:targetPackage="${'$'}{applicationId}" />""".repeat(2)
        assertEquals(
            """<intent android:targetPackage="io.put.putio.mobile.nightly.debug" />""".repeat(2),
            renderShortcuts(template, "io.put.putio.mobile.nightly.debug"),
        )
    }

    @Test
    fun aTemplateWithoutThePlaceholderOrABadIdFails() {
        assertThrows(GradleException::class.java) { renderShortcuts("<shortcuts />", "io.put.putio.mobile") }
        assertThrows(GradleException::class.java) {
            renderShortcuts("${'$'}{applicationId}", "io.put.putio.mobile\" android:x=\"")
        }
    }
}
