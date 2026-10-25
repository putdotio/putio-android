package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import io.putdotio.android.files.MOBILE_FILES_MOVE_OPTIONS_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_REMEMBER_TAG

/** A Files row by its full name; folders have no overflow button, so their actions open from here. */
internal fun SemanticsNodeInteractionsProvider.filesRow(name: String): SemanticsNodeInteraction =
    onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

/** The move picker's Remember target folder, opening its overflow first when it is closed. */
internal fun SemanticsNodeInteractionsProvider.moveRememberTarget(): SemanticsNodeInteraction {
    if (onAllNodes(hasTestTag(MOBILE_FILES_MOVE_REMEMBER_TAG)).fetchSemanticsNodes().isEmpty()) {
        onNode(hasTestTag(MOBILE_FILES_MOVE_OPTIONS_TAG)).performClick()
    }
    return onNode(hasTestTag(MOBILE_FILES_MOVE_REMEMBER_TAG))
}

/** Opens a row's actions sheet the way a long-press does. */
internal fun SemanticsNodeInteractionsProvider.openRowActions(name: String) {
    filesRow(name).performSemanticsAction(SemanticsActions.OnLongClick)
}
