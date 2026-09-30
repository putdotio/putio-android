import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

abstract class VerifyInstrumentationProofTask : DefaultTask() {
    @get:InputDirectory
    abstract val resultsDirectory: DirectoryProperty

    /** Fully qualified `Class#method` whose passing XML result is the proof. */
    @get:Input
    abstract val requiredTest: Property<String>

    @TaskAction
    fun verify() {
        requireSuccessfulInstrumentationResult(resultsDirectory.get().asFile, requiredTest.get())
    }
}

internal fun requireSuccessfulInstrumentationResult(directory: File, requiredTest: String) {
    val className = requiredTest.substringBefore('#')
    val methodName = requiredTest.substringAfter('#', missingDelimiterValue = "")
    require(className.isNotEmpty() && methodName.isNotEmpty()) {
        "requiredTest must be Class#method, got '$requiredTest'"
    }
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    var successfulTests = 0
    for (report in directory.walkTopDown().filter { it.isFile && it.extension == "xml" }) {
        val document = factory.newDocumentBuilder().parse(report)
        val cases = document.getElementsByTagName("testcase")
        for (index in 0 until cases.length) {
            val test = cases.item(index) as? org.w3c.dom.Element
                ?: throw GradleException("Invalid testcase element in ${report.name}")
            if (test.getAttribute("classname") != className || test.getAttribute("name") != methodName) continue

            if (listOf("failure", "error", "skipped").any { test.getElementsByTagName(it).length > 0 }) {
                throw GradleException("$requiredTest did not pass: ${report.name}")
            }
            successfulTests++
        }
    }
    if (successfulTests == 0) {
        throw GradleException("No successful $requiredTest result; instrumentation exit status alone is not proof")
    }
}
