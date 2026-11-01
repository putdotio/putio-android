package io.putdotio.android.tv.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.tv.TvFocus
import kotlin.math.roundToInt

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

private fun handleFieldKey(
    event: KeyEvent,
    state: TextFieldState,
    focusManager: FocusManager,
    onCenter: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val selection = state.selection
    return when (event.key) {
        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
        Key.DirectionLeft ->
            selection.collapsed && selection.start == 0 && focusManager.moveFocus(FocusDirection.Left)
        Key.DirectionRight ->
            selection.collapsed && selection.end == state.text.length &&
                focusManager.moveFocus(FocusDirection.Right)
        Key.DirectionCenter -> {
            onCenter()
            true
        }
        else -> false
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
    val focused by interaction.collectIsFocusedAsState()
    val ring = TvFocus.container
    val gap = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .drawBehind {
                if (focused) {
                    drawPill(gap, RING_GAP.toPx() / 2, RING_GAP.toPx())
                    drawPill(ring, (RING_GAP + RING_WIDTH / 2).toPx(), RING_WIDTH.toPx())
                }
            }
            .background(PutioDesignTokens.componentBgActive, CircleShape)
            .focusGroup(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FIELD_HEIGHT)
                .padding(start = FIELD_PADDING, end = FIELD_END_PADDING),
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

@OptIn(ExperimentalLayoutApi::class)
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
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var keyboardRequested by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, focused) { if (!imeVisible || !focused) keyboardRequested = false }
    var resummon by remember { mutableStateOf(false) }
    LaunchedEffect(resummon) {
        if (!resummon) return@LaunchedEffect
        withFrameNanos {}
        keyboardRequested = true
        resummon = false
    }
    val label = stringResource(R.string.tv_search_field)
    val scroll = rememberScrollState()
    val layout = remember { TvSearchCaretLayout() }
    val caretColor = MaterialTheme.colorScheme.primary
    BasicTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        textStyle = FIELD_TEXT.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(Color.Unspecified),
        scrollState = scroll,
        onTextLayout = { result -> layout.result = result },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = keyboardRequested),
        onKeyboardAction = {
            keyboard?.hide()
            submit(state, rewrite, actions.onSubmit)
        },
        modifier = modifier
            .semantics { contentDescription = label }
            .testTag(TV_SEARCH_FIELD_TAG)
            .onPreviewKeyEvent { event ->
                val menu = actions.onMenu
                if (event.key == Key.Menu && menu != null) {
                    if (event.type == KeyEventType.KeyUp) menu()
                    return@onPreviewKeyEvent true
                }
                handleFieldKey(event, state, focusManager) {
                    if (keyboardRequested) {
                        keyboardRequested = false
                        resummon = true
                    } else {
                        keyboardRequested = true
                    }
                }
            },
        decorator = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tv_search_placeholder),
                        style = FIELD_TEXT,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier.drawWithContent {
                        drawContent()
                        val result = layout.result() ?: return@drawWithContent
                        if (focused || state.text.isNotEmpty()) {
                            drawCaret(result, state.selection.end, scroll.value, caretColor)
                        }
                    },
                ) { inner() }
            }
        },
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

private class TvSearchCaretLayout {
    var result: () -> TextLayoutResult? = { null }
}

private fun DrawScope.drawCaret(result: TextLayoutResult, offset: Int, scroll: Int, color: Color) {
    val width = CARET_WIDTH.toPx()
    val height = CARET_HEIGHT.toPx()
    val left = (result.getCursorRect(offset).left - scroll + CARET_GAP.toPx())
        .coerceIn(0f, (size.width - width).coerceAtLeast(0f))
        .roundToInt()
        .toFloat()
    val bottom = result.getLineBaseline(result.getLineForOffset(offset)) + CARET_DROP.toPx()
    drawRect(color, Offset(left, bottom - height), Size(width, height))
}

private fun DrawScope.drawPill(color: Color, reach: Float, width: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-reach, -reach),
        size = Size(size.width + 2 * reach, size.height + 2 * reach),
        cornerRadius = CornerRadius(size.height / 2 + reach),
        style = Stroke(width),
    )
}

internal val TV_SEARCH_FIELD_HEIGHT = 40.dp
private val FIELD_HEIGHT = TV_SEARCH_FIELD_HEIGHT
private val FIELD_PADDING = 18.dp
private val CLEAR_SIZE = 28.dp
private val FIELD_ICON = 18.dp
private val FIELD_END_PADDING = FIELD_PADDING - (CLEAR_SIZE - FIELD_ICON) / 2
private val FIELD_GAP = 12.dp
private val RING_GAP = 2.dp
private val RING_WIDTH = 2.dp
private val FIELD_TEXT = PutioTypeScale.bodySm
private val CARET_WIDTH = 1.5.dp
private val CARET_HEIGHT = 20.dp
private val CARET_GAP = 1.dp
private val CARET_DROP = 4.dp
