package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme

/** [content] in the app's Compose for TV theme. */
internal fun ComposeContentTestRule.setTvContent(content: @Composable () -> Unit) {
    setContent { MaterialTheme(colorScheme = putioTvDarkColorScheme(), content = content) }
}

/** A D-pad Center tap with no hold between down and up. */
@OptIn(ExperimentalTestApi::class)
internal fun KeyInjectionScope.pressCenter() {
    keyDown(Key.DirectionCenter)
    keyUp(Key.DirectionCenter)
}
