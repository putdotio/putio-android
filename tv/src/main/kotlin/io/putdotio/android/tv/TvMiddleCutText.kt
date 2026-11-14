package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import io.putdotio.android.design.PutioMiddleCutText

@Composable
internal fun TvMiddleCutText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    PutioMiddleCutText(
        text = text,
        style = style,
        color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
        modifier = modifier,
    )
}
