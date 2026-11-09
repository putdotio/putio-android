package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import kotlin.math.roundToInt

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
internal fun TvTextFieldFrame(
    interaction: MutableInteractionSource,
    ringGap: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val focused by interaction.collectIsFocusedAsState()
    val ring = TvFocus.container
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TV_TEXT_FIELD_HEIGHT)
            .drawBehind {
                if (focused) {
                    drawPill(ringGap, RING_GAP.toPx() / 2, RING_GAP.toPx())
                    drawPill(ring, (RING_GAP + RING_WIDTH / 2).toPx(), RING_WIDTH.toPx())
                }
            }
            .background(PutioDesignTokens.componentBgActive, CircleShape)
            .focusGroup(),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvTextInput(
    state: TextFieldState,
    label: String,
    placeholder: String?,
    imeAction: ImeAction,
    interaction: MutableInteractionSource,
    testTag: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onMenu: (() -> Unit)? = null,
) {
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
    val scroll = rememberScrollState()
    val layout = remember { TvCaretLayout() }
    val caretColor = MaterialTheme.colorScheme.primary
    BasicTextField(
        state = state,
        readOnly = readOnly,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        textStyle = TV_FIELD_TEXT.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(Color.Unspecified),
        scrollState = scroll,
        onTextLayout = { result -> layout.result = result },
        keyboardOptions = KeyboardOptions(imeAction = imeAction, showKeyboardOnFocus = keyboardRequested),
        onKeyboardAction = {
            keyboard?.hide()
            onSubmit()
        },
        modifier = modifier
            .semantics { contentDescription = label }
            .testTag(testTag)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Menu && onMenu != null) {
                    if (event.type == KeyEventType.KeyUp) onMenu()
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
                if (state.text.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = TV_FIELD_TEXT,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier.drawWithContent {
                        drawContent()
                        val result = layout.result() ?: return@drawWithContent
                        if (state.selection.collapsed && (focused || state.text.isNotEmpty())) {
                            drawCaret(result, state.selection.end, scroll.value, caretColor)
                        }
                    },
                ) { inner() }
            }
        },
    )
}

private class TvCaretLayout {
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

internal val TV_TEXT_FIELD_HEIGHT = 40.dp
internal val TV_TEXT_FIELD_PADDING = 18.dp
private val RING_GAP = 2.dp
private val RING_WIDTH = 2.dp
private val TV_FIELD_TEXT = PutioTypeScale.bodySm
private val CARET_WIDTH = 1.5.dp
private val CARET_HEIGHT = 20.dp
private val CARET_GAP = 1.dp
private val CARET_DROP = 4.dp
