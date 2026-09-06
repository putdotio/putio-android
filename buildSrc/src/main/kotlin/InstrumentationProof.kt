import org.gradle.api.GradleException

/** Validates the raw `am instrument -w -r` result for one explicitly selected test. */
internal fun requireSuccessfulInstrumentation(output: String, className: String, testName: String) {
    val status = mutableMapOf<String, String>()
    var completedStatuses = 0
    var finished = false

    fun reject(reason: String): Nothing =
        throw GradleException("Authenticated instrumentation proof failed: $reason")

    for (line in output.lineSequence()) {
        when {
            line.startsWith("INSTRUMENTATION_STATUS: ") -> {
                if (finished) reject("status after terminal result")
                val field = line.removePrefix("INSTRUMENTATION_STATUS: ")
                val separator = field.indexOf('=')
                if (separator < 1) reject("malformed status field")
                val key = field.substring(0, separator)
                if (status.put(key, field.substring(separator + 1)) != null) {
                    reject("duplicate status field")
                }
            }
            line.startsWith("INSTRUMENTATION_STATUS_CODE:") -> {
                if (finished) reject("status after terminal result")
                if (status["class"] != className || status["test"] != testName) {
                    reject("missing or unexpected test identity")
                }
                if (listOf("numtests", "current").any { key ->
                        key in status && status[key]?.toIntOrNull() != 1
                    }) {
                    reject("expected exactly one selected test")
                }
                val code = line.substringAfter(':').trim().toIntOrNull()
                val expected = when (completedStatuses) {
                    0 -> 1
                    1 -> 0
                    else -> reject("duplicate test result")
                }
                if (code != expected) reject("test did not report start followed by success")
                completedStatuses++
                status.clear()
            }
            line.startsWith("INSTRUMENTATION_CODE:") -> {
                if (finished) reject("duplicate terminal result")
                if (status.isNotEmpty()) reject("incomplete status block")
                if (completedStatuses != 2) reject("missing successful test result")
                // Activity.RESULT_OK is -1 here; status -1 above means a test error.
                if (line.substringAfter(':').trim().toIntOrNull() != -1) {
                    reject("unsuccessful terminal result")
                }
                finished = true
            }
            line.startsWith("INSTRUMENTATION_FAILED:") ||
                line.startsWith("INSTRUMENTATION_ABORTED:") -> reject("runner failed")
            line.startsWith("INSTRUMENTATION_RESULT: ") -> {
                if (finished) reject("result after terminal result")
                val key = line.removePrefix("INSTRUMENTATION_RESULT: ").substringBefore('=')
                if (key == "shortMsg" || key == "longMsg") reject("runner reported an error")
            }
        }
    }
    if (!finished) reject("missing terminal result")
}
