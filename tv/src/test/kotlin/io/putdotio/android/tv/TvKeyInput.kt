package io.putdotio.android.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope

/** A D-pad Center tap with no hold between down and up. */
@OptIn(ExperimentalTestApi::class)
internal fun KeyInjectionScope.pressCenter() {
    keyDown(Key.DirectionCenter)
    keyUp(Key.DirectionCenter)
}
