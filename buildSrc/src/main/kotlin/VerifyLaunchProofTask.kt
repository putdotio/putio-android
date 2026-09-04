import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

abstract class VerifyLaunchProofTask : DefaultTask() {
    @get:InputDirectory
    abstract val resultsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        requireSuccessfulLaunchProof(resultsDirectory.get().asFile)
    }
}

internal fun requireSuccessfulLaunchProof(directory: File) {
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
            if (
                test.getAttribute("classname") != "io.putdotio.android.LaunchSmokeTest" ||
                test.getAttribute("name") != "shellLaunchesStaysResumedAndRenders"
            ) continue

            if (listOf("failure", "error", "skipped").any { test.getElementsByTagName(it).length > 0 }) {
                throw GradleException("LaunchSmokeTest did not pass: ${report.name}")
            }
            successfulTests++
        }
    }
    if (successfulTests == 0) {
        throw GradleException("No successful LaunchSmokeTest result; instrumentation exit status alone is not proof")
    }
}
