package io.putdotio.android.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.tv.auth.TvAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The signed-in shell keeps every label and focus target inside the overscan
 * safe area, the `tv.overscan` fractions of whatever viewport it fills.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvSafeAreaTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun railAndPaneStayInsideTheSafeArea() {
        showShell()
        assertContentInsideSafeArea()
    }

    @Test
    fun theExpandedDrawerStaysInsideTheSafeArea() {
        showShell()
        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertExists()
        assertContentInsideSafeArea()
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-television")
    fun theSafeAreaScalesWithTheViewport() {
        showShell()
        assertContentInsideSafeArea()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-television-xxxhdpi")
    fun aFourKPanelKeepsTheSameProportionClear() {
        showShell()
        assertContentInsideSafeArea()
    }

    @Test
    fun layoutsKeepFortyEightDpSidesAndTwentyFourDpTopAndBottom() {
        assertLayoutMargins(sideDp = 48f, verticalDp = 24f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-television")
    fun theOverscanSafeAreaWinsWhereItIsWider() {
        assertLayoutMargins(sideDp = 1280f * PutioDesignTokens.tvOverscanX, verticalDp = 24f)
    }

    private fun assertLayoutMargins(sideDp: Float, verticalDp: Float) {
        compose.setTvContent {
            Box(Modifier.fillMaxSize().tvSafeAreaPadding()) { Box(Modifier.fillMaxSize().testTag(CONTENT)) }
        }
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag(CONTENT).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals(sideDp, content.left / density, MARGIN_TOLERANCE_DP)
        assertEquals(sideDp, (viewport.right - content.right) / density, MARGIN_TOLERANCE_DP)
        assertEquals(verticalDp, content.top / density, MARGIN_TOLERANCE_DP)
        assertEquals(verticalDp, (viewport.bottom - content.bottom) / density, MARGIN_TOLERANCE_DP)
    }

    private fun showShell() {
        compose.setTvContent {
            TvShell(account = TvAccount(userId = 1, username = "user", email = "user@example.com"), onSignOut = {})
        }
        compose.waitForIdle()
    }

    private fun assertContentInsideSafeArea() {
        val placed = compose.onAllNodes(visibleContent, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("expected the wordmark, drawer items and pane, found ${placed.size} nodes", placed.size >= 6)
        compose.assertInsideTvSafeArea(placed)
    }

    private companion object {
        const val CONTENT = "content"

        /** One pixel of rounding at the test density. */
        const val MARGIN_TOLERANCE_DP = 1f

        val visibleContent = SemanticsMatcher("text, description or click target") {
            it.config.contains(SemanticsProperties.Text) ||
                it.config.contains(SemanticsProperties.ContentDescription) ||
                it.config.contains(SemanticsActions.OnClick)
        }
    }
}

/** Fails unless every one of [nodes] lies inside the `tv.overscan` fractions of the root viewport. */
internal fun SemanticsNodeInteractionsProvider.assertInsideTvSafeArea(nodes: List<SemanticsNode>) {
    val viewport = onRoot().fetchSemanticsNode().boundsInRoot
    val safe = Rect(
        left = viewport.width * PutioDesignTokens.tvOverscanX,
        top = viewport.height * PutioDesignTokens.tvOverscanY,
        right = viewport.width * (1 - PutioDesignTokens.tvOverscanX),
        bottom = viewport.height * (1 - PutioDesignTokens.tvOverscanY),
    )
    nodes.forEach { node ->
        val bounds = node.boundsInRoot
        val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: "node ${node.id}"
        assertTrue(
            "$label at $bounds leaves the safe area $safe of $viewport",
            bounds.left >= safe.left - SAFE_AREA_EPSILON && bounds.top >= safe.top - SAFE_AREA_EPSILON &&
                bounds.right <= safe.right + SAFE_AREA_EPSILON && bounds.bottom <= safe.bottom + SAFE_AREA_EPSILON,
        )
    }
}

/** Sub-pixel float noise from scaled focus targets, not a layout tolerance. */
private const val SAFE_AREA_EPSILON = 0.5f
