package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text

/** One selectable row of a [TvChoiceDialog]. */
internal data class TvChoice<T>(
    val value: T,
    val label: String,
)

/**
 * Centred M3 choice dialog per the TV contract: 28dp corner, one radio row per
 * choice, focus lands on the current choice (or the first row when there is none),
 * Back dismisses without a change.
 */
@Composable
internal fun <T> TvChoiceDialog(
    title: String,
    choices: List<TvChoice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedFocus = remember { FocusRequester() }
    val bringSelectedIntoView = remember { BringIntoViewRequester() }
    Dialog(onDismissRequest = onDismiss) {
        // Requested from inside the dialog window, after its content has attached. The
        // column is scrolled first so a choice below the fold is on screen when it lights up.
        LaunchedEffect(Unit) {
            // The rows are laid out one frame after the window opens.
            withFrameNanos {}
            bringSelectedIntoView.bringIntoView()
            selectedFocus.requestFocus()
        }
        Column(
            modifier = Modifier
                .width(560.dp)
                // Twelve rows outgrow a 540dp canvas; the bound is what lets the column scroll.
                .heightIn(max = 480.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            val selectedIndex = choices.indexOfFirst { it.value == selected }
            choices.forEachIndexed { index, choice ->
                val isSelected = index == selectedIndex
                val focusTarget = isSelected || (selectedIndex < 0 && index == 0)
                ListItem(
                    selected = isSelected,
                    onClick = { onSelect(choice.value) },
                    headlineContent = { Text(choice.label) },
                    leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (focusTarget) {
                                Modifier
                                    .bringIntoViewRequester(bringSelectedIntoView)
                                    .focusRequester(selectedFocus)
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                )
            }
        }
    }
}
