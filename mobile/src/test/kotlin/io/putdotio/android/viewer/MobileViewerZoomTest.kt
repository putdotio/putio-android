package io.putdotio.android.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileViewerZoomTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDoubleTapZoomsInAndASecondFitsAgain() {
        val zoom = MobileViewerZoom()
        compose.setContent { Box(Modifier.fillMaxSize().viewerZoom(zoom).testTag(PAGE)) }

        compose.onNodeWithTag(PAGE).performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertTrue(zoom.zoomed) }

        compose.onNodeWithTag(PAGE).performTouchInput { doubleClick(center) }
        compose.runOnIdle {
            assertEquals(1f, zoom.scale)
            assertEquals(Offset.Zero, zoom.offset)
        }
    }

    @Test
    fun aDoubleTapThatJittersStillFitsAZoomedPage() {
        val zoom = MobileViewerZoom()
        compose.setContent { Box(Modifier.fillMaxSize().viewerZoom(zoom).testTag(PAGE)) }
        compose.onNodeWithTag(PAGE).performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertTrue(zoom.zoomed) }

        compose.onNodeWithTag(PAGE).performTouchInput {
            repeat(2) {
                down(center)
                moveBy(Offset(2f, 1f))
                up()
                advanceEventTime(100)
            }
        }

        compose.runOnIdle { assertFalse(zoom.zoomed) }
    }

    @Test
    fun aPinchZoomsIn() {
        val zoom = MobileViewerZoom()
        compose.setContent { Box(Modifier.fillMaxSize().viewerZoom(zoom).testTag(PAGE)) }

        compose.onNodeWithTag(PAGE).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(200f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(200f, 0f),
            )
        }

        compose.runOnIdle { assertTrue(zoom.zoomed) }
    }

    @Test
    fun oneFingerAtFitStillSwipesThePager() {
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState { 2 }
            HorizontalPager(pager, Modifier.fillMaxSize().testTag(PAGE)) {
                Box(Modifier.fillMaxSize().viewerZoom(MobileViewerZoom()))
            }
        }

        compose.onNodeWithTag(PAGE).performTouchInput { swipeLeft() }

        compose.runOnIdle { assertEquals(1, pager.settledPage) }
    }

    @Test
    fun theContentUnderThePinchStaysUnderIt() {
        val zoom = MobileViewerZoom().apply { measure(IntSize(1000, 2000), contentAspect = null) }
        val centroid = Offset(700f, 1300f)
        val before = contentAt(zoom, IntSize(1000, 2000), centroid)

        zoom.transform(zoomChange = 2f, centroid = centroid, pan = Offset.Zero)

        val after = contentAt(zoom, IntSize(1000, 2000), centroid)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test
    fun aPinchKeepsTheContentUnderAStillFinger() {
        val zoom = MobileViewerZoom()
        compose.setContent { Box(Modifier.fillMaxSize().viewerZoom(zoom).testTag(PAGE)) }
        val node = compose.onNodeWithTag(PAGE)
        val viewport = node.fetchSemanticsNode().size
        var still = Offset.Zero

        node.performTouchInput {
            still = center - Offset(100f, 0f)
            down(0, still)
            down(1, center + Offset(100f, 0f))
            repeat(10) { moveBy(1, Offset(30f, 0f)) }
            up(1)
            up(0)
        }

        compose.runOnIdle {
            assertEquals(2.5f, zoom.scale, 0.01f)
            assertEquals(still.x, contentAt(zoom, viewport, still).x, 0.5f)
        }
    }

    @Test
    fun aSmallerFrameKeepsTheZoomedContentEdgeAtItsBorder() {
        val zoom = MobileViewerZoom().apply { measure(IntSize(2000, 1000), contentAspect = 2f) }
        zoom.transform(zoomChange = 2.5f, centroid = Offset(1000f, 500f), pan = Offset(5000f, 0f))

        zoom.measure(IntSize(1000, 2000), contentAspect = 2f)

        assertEquals((1000f * 2.5f - 1000f) / 2f, zoom.offset.x, 0.01f)
    }

    @Test
    fun aPanNeverMovesTheContentEdgeInsideTheFrame() {
        val zoom = MobileViewerZoom().apply { measure(IntSize(1000, 2000), contentAspect = 1000f / 600f) }
        zoom.transform(zoomChange = 3f, centroid = Offset(500f, 1000f), pan = Offset.Zero)

        val overflow = zoom.transform(zoomChange = 1f, centroid = Offset(500f, 1000f), pan = Offset(5000f, 5000f))

        assertEquals((1000f * 3f - 1000f) / 2f, zoom.offset.x, 0.01f)
        assertEquals(0f, zoom.offset.y, 0.01f)
        assertTrue(overflow.x > 0f && overflow.y > 0f)
    }

    @Test
    fun zoomingOutAtAnEdgeOverflowsNothing() {
        val zoom = MobileViewerZoom().apply { measure(IntSize(1000, 2000), contentAspect = null) }
        zoom.transform(zoomChange = 3f, centroid = Offset(500f, 1000f), pan = Offset(0f, 5000f))

        val overflow = zoom.transform(zoomChange = 0.5f, centroid = Offset(500f, 1000f), pan = Offset.Zero)

        assertEquals(Offset.Zero, overflow)
    }

    @Test
    fun zoomStaysBetweenFitAndTheMaximum() {
        val zoom = MobileViewerZoom().apply { measure(IntSize(1000, 1000), contentAspect = null) }

        zoom.transform(zoomChange = 0.2f, centroid = Offset(500f, 500f), pan = Offset.Zero)
        assertFalse(zoom.zoomed)
        assertEquals(Offset.Zero, zoom.offset)

        zoom.transform(zoomChange = 100f, centroid = Offset(500f, 500f), pan = Offset.Zero)
        assertEquals(VIEWER_ZOOM_MAX, zoom.scale)
    }

    private fun contentAt(zoom: MobileViewerZoom, viewport: IntSize, screen: Offset): Offset {
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        return (screen - center - zoom.offset) / zoom.scale + center
    }

    private companion object {
        const val PAGE = "page"
    }
}
