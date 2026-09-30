import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/** Fails unless the merged manifest lists [activity] for each launcher's `ACTION_MAIN` query. */
abstract class VerifyLauncherManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** Fully qualified activity class name as written in the merged manifest. */
    @get:Input
    abstract val activity: Property<String>

    /** Categories a launcher pairs with `android.intent.action.MAIN`, e.g. `LEANBACK_LAUNCHER`. */
    @get:Input
    abstract val launcherCategories: ListProperty<String>

    @TaskAction
    fun verify() {
        requireLauncherEntries(mergedManifest.get().asFile, activity.get(), launcherCategories.get())
    }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val ACTION_MAIN = "android.intent.action.MAIN"

internal fun requireLauncherEntries(manifest: File, activity: String, categories: List<String>) {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(manifest)
    val activities = document.getElementsByTagName("activity").elements()
        .filter { it.getAttributeNS(ANDROID_NS, "name") == activity }
    if (activities.isEmpty()) throw GradleException("${manifest.name} declares no $activity")

    // A launcher query carries no data, so a filter with <data> cannot match it.
    val filters = activities.flatMap { it.getElementsByTagName("intent-filter").elements() }
        .filter { it.getElementsByTagName("data").length == 0 }
    val missing = categories.filterNot { category ->
        filters.any { filter ->
            filter.androidNames("action").contains(ACTION_MAIN) && filter.androidNames("category").contains(category)
        }
    }
    if (missing.isNotEmpty()) {
        throw GradleException("$activity has no $ACTION_MAIN intent filter for ${missing.joinToString()}")
    }
}

private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

private fun Element.androidNames(tag: String): Set<String> =
    getElementsByTagName(tag).elements().map { it.getAttributeNS(ANDROID_NS, "name") }.toSet()
