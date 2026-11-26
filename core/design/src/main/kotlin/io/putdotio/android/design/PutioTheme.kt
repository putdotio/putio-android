package io.putdotio.android.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
public fun PutioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = putioDarkColorScheme(),
        typography = putioTypography,
        shapes = putioShapes,
        content = content,
    )
}

internal val putioTypography: Typography = Typography().inUiFace().run {
    copy(
        headlineMedium = headlineMedium.merge(PutioTypeScale.headlineLg),
        headlineSmall = headlineSmall.merge(PutioTypeScale.titleLg),
        titleLarge = titleLarge.merge(PutioTypeScale.titleLg),
        titleSmall = titleSmall.merge(PutioTypeScale.labelMd),
        bodyLarge = bodyLarge.merge(PutioTypeScale.bodyLg),
        bodyMedium = bodyMedium.merge(PutioTypeScale.bodySm),
        bodySmall = bodySmall.merge(PutioTypeScale.meta),
        labelMedium = labelMedium.merge(PutioTypeScale.labelSm),
        labelSmall = labelSmall.merge(PutioTypeScale.labelSm),
    )
}

private fun Typography.inUiFace(): Typography {
    fun TextStyle.ui() = copy(fontFamily = PutioFontFamily.gtAmerica)
    return copy(
        displayLarge = displayLarge.ui(),
        displayMedium = displayMedium.ui(),
        displaySmall = displaySmall.ui(),
        headlineLarge = headlineLarge.ui(),
        headlineMedium = headlineMedium.ui(),
        headlineSmall = headlineSmall.ui(),
        titleLarge = titleLarge.ui(),
        titleMedium = titleMedium.ui(),
        titleSmall = titleSmall.ui(),
        bodyLarge = bodyLarge.ui(),
        bodyMedium = bodyMedium.ui(),
        bodySmall = bodySmall.ui(),
        labelLarge = labelLarge.ui(),
        labelMedium = labelMedium.ui(),
        labelSmall = labelSmall.ui(),
    )
}

internal val putioShapes: Shapes = Shapes().copy(
    extraSmall = RoundedCornerShape(PutioRadius.extraSmall),
    small = RoundedCornerShape(PutioRadius.small),
    medium = RoundedCornerShape(PutioRadius.medium),
    large = RoundedCornerShape(PutioRadius.large),
    extraLarge = RoundedCornerShape(PutioRadius.extraLarge),
)

private val MINIMUM_NUMBER_SIZE = 12.sp

@Composable
@ReadOnlyComposable
public fun TextStyle.putioMono(): TextStyle {
    val inherited = LocalTextStyle.current
    return monoNumbers(inherited.merge(this), inherited.fontSize)
}

internal fun monoNumbers(style: TextStyle, inheritedSize: TextUnit = TextUnit.Unspecified): TextStyle {
    val size = if (style.fontSize.isEm && inheritedSize.isSp) {
        (inheritedSize.value * style.fontSize.value).sp
    } else {
        style.fontSize
    }
    val belowFloor = size.isSp && size < MINIMUM_NUMBER_SIZE
    return style.copy(
        fontFamily = PutioTypeScale.mono.fontFamily,
        fontWeight = PutioTypeScale.mono.fontWeight,
        fontFeatureSettings = PutioTypeScale.mono.fontFeatureSettings,
        fontSize = if (belowFloor) MINIMUM_NUMBER_SIZE else size,
    )
}
