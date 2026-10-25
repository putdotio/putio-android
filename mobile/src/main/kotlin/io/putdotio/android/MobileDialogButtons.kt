package io.putdotio.android

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** DESIGN.md keeps every button but the one primary action neutral; Material's text button is yellow. */
@Composable
internal fun neutralTextButtonColors(): ButtonColors =
    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

/** A dialog's Cancel: a neutral text button, so the dialog's one yellow action stays its confirm. */
@Composable
internal fun MobileDialogDismissButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = stringResource(R.string.mobile_action_cancel),
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = neutralTextButtonColors()) {
        Text(text)
    }
}
