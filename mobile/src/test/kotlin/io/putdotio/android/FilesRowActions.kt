package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction

/** A Files row by its full name; folders have no overflow button, so their actions open from here. */
internal fun SemanticsNodeInteractionsProvider.filesRow(name: String): SemanticsNodeInteraction =
    onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

/** Opens a row's actions sheet the way a long-press does. */
internal fun SemanticsNodeInteractionsProvider.openRowActions(name: String) {
    filesRow(name).performSemanticsAction(SemanticsActions.OnLongClick)
}
