package io.putdotio.android

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert

internal fun SemanticsNodeInteraction.assertIsButton(): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
