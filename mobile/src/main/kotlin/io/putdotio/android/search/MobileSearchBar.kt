package io.putdotio.android.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.R

internal const val MOBILE_SEARCH_FIELD_TAG = "mobile-search-field"
internal const val MOBILE_SEARCH_CLEAR_TAG = "mobile-search-clear"

/**
 * Expanded search's bar, in the Files pill's place: back, the query, then clear once there is one,
 * on surface-container-highest. A folder's search names the folder in its hint. The field starts
 * from [query] and owns its text from then on; [replacement] is how anything else changes it. Like
 * the top bars it replaces, it owns the status bar inset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileSearchBar(
    query: String,
    scope: SearchScope?,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    focusRequested: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    replacement: MobileSearchFieldText? = null,
    onReplaced: () -> Unit = {},
    navigation: (@Composable () -> Unit)? = null,
) {
    val field = remember { FocusRequester() }
    val text = rememberQueryText(query, replacement, onReplaced, onQueryChanged)
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            field.requestFocus()
            onFocused()
        }
    }
    val hint = scope?.folderName?.let { stringResource(R.string.mobile_files_search_in, it) }
        ?: stringResource(R.string.mobile_files_search_hint)
    Box(modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                .fillMaxWidth()
                // Larger text grows the pill rather than crop the query.
                .heightIn(min = SEARCH_BAR_HEIGHT),
        ) {
            SearchBarDefaults.InputField(
                state = text.state,
                // The IME can submit before the last edit has been sent up; send it first.
                onSearch = {
                    text.send(onQueryChanged)
                    onSubmit()
                },
                expanded = true,
                onExpandedChange = {},
                // The pill draws the container; the field's own would cover it in surface-container-high.
                colors = SearchBarDefaults.inputFieldColors().copy(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .testTag(MOBILE_SEARCH_FIELD_TAG),
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ph_arrow_left),
                            contentDescription = stringResource(R.string.mobile_action_back),
                            modifier = Modifier.size(SEARCH_GLYPH_SIZE),
                        )
                    }
                },
                trailingIcon = if (text.state.text.isEmpty() && navigation == null) {
                    null
                } else {
                    { MobileSearchBarTrailing(text.state, field, navigation) }
                },
            )
        }
    }
}

/** The drawer's menu button when the tabs live there, then clear once there is a query. */
@Composable
private fun MobileSearchBarTrailing(
    text: TextFieldState,
    field: FocusRequester,
    navigation: (@Composable () -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        navigation?.invoke()
        if (text.text.isNotEmpty()) {
            IconButton(
                onClick = {
                    text.clearText()
                    field.requestFocus()
                },
                modifier = Modifier.testTag(MOBILE_SEARCH_CLEAR_TAG),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_x),
                    contentDescription = stringResource(R.string.mobile_search_clear_query),
                )
            }
        }
    }
}

/** Text something other than typing puts in the field, such as a recent search; each is put there once. */
internal class MobileSearchFieldText(val text: String)

/**
 * The field's text. It starts from the query and edits itself, sending each edit up; only an
 * explicit [MobileSearchFieldText] replaces it, with the caret at its end. A new scope starts a new
 * field, so the query never has to be matched against edits still on their way up.
 */
private class MobileQueryText(val state: TextFieldState, initial: String) {
    private var sent = initial

    /** Sends the text up unless it was the last sent or put there. */
    fun send(onQueryChanged: (String) -> Unit) {
        val typed = state.text.toString()
        if (typed != sent) {
            sent = typed
            onQueryChanged(typed)
        }
    }

    fun replace(text: String) {
        sent = text
        state.setTextAndPlaceCursorAtEnd(text)
    }
}

@Composable
private fun rememberQueryText(
    query: String,
    replacement: MobileSearchFieldText?,
    onReplaced: () -> Unit,
    onQueryChanged: (String) -> Unit,
): MobileQueryText {
    val state = rememberTextFieldState(query, TextRange(query.length))
    val text = remember(state) { MobileQueryText(state, query) }
    val currentOnQueryChanged by rememberUpdatedState(onQueryChanged)
    val currentOnReplaced by rememberUpdatedState(onReplaced)
    LaunchedEffect(replacement) {
        if (replacement != null) {
            text.replace(replacement.text)
            currentOnReplaced()
        }
    }
    LaunchedEffect(text) {
        snapshotFlow { state.text.toString() }.collect { text.send(currentOnQueryChanged) }
    }
    return text
}

private val SEARCH_BAR_HEIGHT = 56.dp
private val SEARCH_GLYPH_SIZE = 22.dp
