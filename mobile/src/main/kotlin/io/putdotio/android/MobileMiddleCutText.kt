package io.putdotio.android

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.putdotio.android.design.PutioMiddleCutText

/** [PutioMiddleCutText] with Material's text style and content colour. */
@Composable
internal fun MobileMiddleCutText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    PutioMiddleCutText(
        text = text,
        style = style,
        contentColor = LocalContentColor.current,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
    )
}
