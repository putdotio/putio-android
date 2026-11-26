package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.PutioDesignTokens

internal object TvFocus {
    val container: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseSurface

    val content: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseOnSurface

    val secondaryContent: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = ON_FOCUS_SECONDARY_ALPHA)
}

@Composable
@ReadOnlyComposable
internal fun tvAccent(focused: Boolean): Color = if (focused) TvFocus.content else PutioDesignTokens.yellowSolid

private const val ON_FOCUS_SECONDARY_ALPHA = 0.74f
