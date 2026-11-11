import org.junit.Assert.assertEquals
import org.junit.Test

class DesignMdLintTest {
    @Test
    fun aCleanReportHasNoErrorsOrWarnings() {
        val report = """
            {
              "findings": [
                {
                  "severity": "info",
                  "message": "Design system defines 26 colors, 16 typography scales, 8 rounding levels, 6 spacing tokens, 32 components.",
                  "rule": "token-summary"
                }
              ],
              "summary": {
                "errors": 0,
                "warnings": 0,
                "infos": 1
              }
            }
        """.trimIndent()
        assertEquals(0 to 0, DesignMdLint.summary(report))
    }

    @Test
    fun warningsCountEvenWhenTheLinterExitsZero() {
        val report = """
            {
              "findings": [
                {
                  "severity": "warning",
                  "path": "typography",
                  "message": "No typography tokens defined. Agents will use default font choices, reducing your control over the design system's typographic identity.",
                  "rule": "missing-typography"
                }
              ],
              "summary": {
                "errors": 0,
                "warnings": 1,
                "infos": 3
              }
            }
        """.trimIndent()
        assertEquals(0 to 1, DesignMdLint.summary(report))
    }
}
