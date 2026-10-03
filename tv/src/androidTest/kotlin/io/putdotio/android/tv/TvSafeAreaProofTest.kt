package io.putdotio.android.tv

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.tv.auth.TvAccount
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * On-device safe-area proof for the signed-in TV shell at the emulator's
 * current `wm size`. Placeholder panes and a synthetic account: no API calls.
 * Screenshots outline the safe edge in red, then the test asserts every label
 * and focus target lies inside it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvSafeAreaProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV safe-area proof requires opt-in", arguments.getString("putio.tv.safearea.enabled") == "true")
                UUID.fromString(requireNotNull(arguments.getString("putio.tv.safearea.runId")))
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun shellStaysInsideTheSafeArea() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                Box(Modifier.fillMaxSize().drawWithContent { drawContent(); outlineSafeArea(size) }) {
                    TvShell(account = TvAccount(userId = 1, username = "proof", email = "proof@example.com"), onSignOut = {})
                }
            }
        }
        compose.waitForIdle()
        val label = compose.onRoot().fetchSemanticsNode().boundsInRoot.let { "${it.width.toInt()}x${it.height.toInt()}" }
        screenshot("shell-$label")
        assertContentInsideSafeArea()

        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertExists()
        compose.waitForIdle()
        screenshot("drawer-$label")
        assertContentInsideSafeArea()
    }

    @Test
    fun inactiveAccountNoticeStaysInsideTheSafeArea() {
        val zone = ZoneId.systemDefault()
        val deletion = LocalDate.now(zone).plusDays(14).atTime(12, 0).atZone(zone).toInstant()
        val account = TvAccount(
            userId = 1,
            username = "proof",
            email = "proof@example.com",
            inactiveNotice = InactiveAccountNotice.Deactivated(deletion),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                Box(Modifier.fillMaxSize().drawWithContent { drawContent(); outlineSafeArea(size) }) {
                    TvShell(account = account, onSignOut = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Your files are still here, but they are scheduled to be deleted in 14 days.")
            .assertExists()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
        val label = compose.onRoot().fetchSemanticsNode().boundsInRoot.let { "${it.width.toInt()}x${it.height.toInt()}" }
        screenshot("inactive-$label")
        assertContentInsideSafeArea()
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.outlineSafeArea(viewport: Size) {
        val safe = safeArea(Rect(Offset.Zero, viewport))
        drawRect(Color.Red, topLeft = safe.topLeft, size = safe.size, style = Stroke(width = 2f))
    }

    private fun assertContentInsideSafeArea() {
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val safe = safeArea(viewport)
        val placed = compose.onAllNodes(visibleContent, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("found only ${placed.size} nodes", placed.size >= 6)
        placed.forEach { node ->
            val bounds = node.boundsInRoot
            val name = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: "node ${node.id}"
            assertTrue(
                "$name at $bounds leaves the safe area $safe of $viewport",
                bounds.left >= safe.left - EPSILON && bounds.top >= safe.top - EPSILON &&
                    bounds.right <= safe.right + EPSILON && bounds.bottom <= safe.bottom + EPSILON,
            )
        }
    }

    private fun safeArea(viewport: Rect) = Rect(
        left = viewport.width * PutioDesignTokens.tvOverscanX,
        top = viewport.height * PutioDesignTokens.tvOverscanY,
        right = viewport.width * (1 - PutioDesignTokens.tvOverscanX),
        bottom = viewport.height * (1 - PutioDesignTokens.tvOverscanY),
    )

    private fun screenshot(label: String) {
        val runId = UUID.fromString(arguments.getString("putio.tv.safearea.runId"))
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-safearea-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private companion object {
        /** Sub-pixel float noise from scaled focus targets, not a layout tolerance. */
        const val EPSILON = 0.5f

        val visibleContent = SemanticsMatcher("text, description or click target") {
            it.config.contains(SemanticsProperties.Text) ||
                it.config.contains(SemanticsProperties.ContentDescription) ||
                it.config.contains(SemanticsActions.OnClick)
        }
    }
}
