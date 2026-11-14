package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import io.putdotio.android.design.LocalPutioMiddleCuts
import io.putdotio.android.design.PutioMiddleCutText
import io.putdotio.android.design.rememberPutioMiddleCuts

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
        contentColor = LocalContentColor.current,
        color = color,
        modifier = modifier,
    )
}

@Composable
internal fun ProvideMiddleCuts(names: List<String>, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPutioMiddleCuts provides rememberPutioMiddleCuts(names), content = content)
}
