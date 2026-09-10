package io.putdotio.android.tv

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme

/**
 * Compact action per the Android TV contract: focused buttons fill `primary`.
 * The stock focused fill is `onSurface` over `inverseOnSurface`, which the put.io
 * scheme binds to the same light token, so the label would vanish on focus.
 */
@Composable
internal fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        content = content,
    )
}
