package io.putdotio.android.tv.search

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.tv.TV_TEXT_FIELD_HEIGHT
import io.putdotio.android.tv.TV_TEXT_FIELD_PADDING
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.TvTextFieldFrame
import io.putdotio.android.tv.TvTextInput

internal const val TV_SEARCH_FIELD_TAG = "tv-search-field"

internal class TvSearchFieldActions(
    val onQueryChanged: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onMenu: (() -> Unit)?,
    val onCleared: () -> Unit,
)

internal fun submit(
    state: TextFieldState,
    rewrite: MutableState<String?>,
    onSubmit: () -> Unit,
) {
    state.rewriteTo(state.text.toString().trim(), rewrite)
    onSubmit()
}

internal fun TextFieldState.rewriteTo(text: String, rewrite: MutableState<String?>) {
    if (this.text.toString() != text) {
        rewrite.value = text
        setTextAndPlaceCursorAtEnd(text)
    }
}

@Composable
internal fun TvSearchField(
    state: TextFieldState,
    rewrite: MutableState<String?>,
    query: String,
    actions: TvSearchFieldActions,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    TvTextFieldFrame(
        interaction = interaction,
        ringGap = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TV_TEXT_FIELD_HEIGHT)
                .padding(start = TV_TEXT_FIELD_PADDING, end = FIELD_END_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_magnifying_glass),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(FIELD_ICON),
            )
            Spacer(Modifier.width(FIELD_GAP))
            TvSearchInput(
                state = state,
                rewrite = rewrite,
                query = query,
                actions = actions,
                interaction = interaction,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
            )
            if (state.text.isNotEmpty()) {
                Spacer(Modifier.width(FIELD_GAP))
                TvSearchClear(
                    onClick = {
                        state.setTextAndPlaceCursorAtEnd("")
                        actions.onCleared()
                    },
                )
            }
        }
    }
}

@Composable
private fun TvSearchInput(
    state: TextFieldState,
    rewrite: MutableState<String?>,
    query: String,
    actions: TvSearchFieldActions,
    interaction: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val currentQuery by rememberUpdatedState(query)
    val currentOnQueryChanged by rememberUpdatedState(actions.onQueryChanged)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            when {
                text == rewrite.value -> rewrite.value = null
                text != currentQuery -> currentOnQueryChanged(text)
            }
        }
    }
    TvTextInput(
        state = state,
        label = stringResource(R.string.tv_search_field),
        placeholder = stringResource(R.string.tv_search_placeholder),
        imeAction = ImeAction.Search,
        interaction = interaction,
        testTag = TV_SEARCH_FIELD_TAG,
        onMenu = actions.onMenu,
        onSubmit = { submit(state, rewrite, actions.onSubmit) },
        modifier = modifier,
    )
}

@Composable
private fun TvSearchClear(onClick: () -> Unit) {
    val description = stringResource(R.string.tv_search_clear)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(CLEAR_SIZE)
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(Modifier.size(CLEAR_SIZE), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ph_x), contentDescription = null, modifier = Modifier.size(FIELD_ICON))
        }
    }
}

internal val TV_SEARCH_FIELD_HEIGHT = TV_TEXT_FIELD_HEIGHT
private val CLEAR_SIZE = 28.dp
private val FIELD_ICON = 18.dp
private val FIELD_END_PADDING = TV_TEXT_FIELD_PADDING - (CLEAR_SIZE - FIELD_ICON) / 2
private val FIELD_GAP = 12.dp
