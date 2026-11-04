package io.putdotio.android.playback

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class TabletopFold(val top: Dp, val bottom: Dp)

@Composable
internal fun tabletopFoldIn(contentInWindow: Rect, enabled: Boolean): TabletopFold? {
    val hinge = currentWindowAdaptiveInfo().windowPosture.hingeList
        .firstOrNull { !it.isVertical && !it.isFlat }
        ?.bounds
    val density = LocalDensity.current
    return if (enabled && hinge != null) tabletopFold(hinge, contentInWindow, density) else null
}

internal fun tabletopFold(hingeInWindow: Rect, contentInWindow: Rect, density: Density): TabletopFold? {
    val top = hingeInWindow.top - contentInWindow.top
    val bottom = hingeInWindow.bottom - contentInWindow.top
    if (top <= 0f || bottom >= contentInWindow.height) return null
    return with(density) { TabletopFold(top.toDp(), bottom.toDp()) }
}

internal fun Modifier.videoArea(fold: TabletopFold?): Modifier =
    if (fold == null) fillMaxSize() else fillMaxWidth().height(fold.top)

internal fun TabletopFold?.videoClearance(footer: Dp): Dp = if (this == null) footer else 0.dp
