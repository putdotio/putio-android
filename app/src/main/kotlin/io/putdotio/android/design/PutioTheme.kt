package io.putdotio.android.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The tier-2 binding from putio-design platforms/android/DESIGN.md: one
 * generated dark color scheme, every component stock Material 3. Dark only;
 * there is no light scheme in the contract.
 */
@Composable
fun PutioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = putioDarkColorScheme(),
        content = content,
    )
}
