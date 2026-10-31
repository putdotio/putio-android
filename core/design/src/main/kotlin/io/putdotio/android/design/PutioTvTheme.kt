package io.putdotio.android.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography

@Composable
public fun PutioTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = putioTvDarkColorScheme(),
        typography = putioTvTypography,
        shapes = putioTvShapes,
        content = content,
    )
}

internal val putioTvTypography: Typography = Typography().run {
    fun TextStyle.ui() = copy(fontFamily = PutioFontFamily.gtAmerica)
    copy(
        displayLarge = displayLarge.ui(),
        displayMedium = displayMedium.ui(),
        displaySmall = displaySmall.merge(PutioTypeScale.tvDetailTitle),
        headlineLarge = headlineLarge.merge(PutioTypeScale.tvTitle),
        headlineMedium = headlineMedium.merge(PutioTypeScale.tvTitle),
        headlineSmall = headlineSmall.merge(PutioTypeScale.tvTitle),
        titleLarge = titleLarge.merge(PutioTypeScale.tvTitle),
        titleMedium = titleMedium.merge(PutioTypeScale.tvBody),
        titleSmall = titleSmall.merge(PutioTypeScale.tvBody),
        bodyLarge = bodyLarge.merge(PutioTypeScale.tvBody),
        bodyMedium = bodyMedium.merge(PutioTypeScale.tvBody),
        bodySmall = bodySmall.merge(PutioTypeScale.tvMeta),
        labelLarge = labelLarge.merge(PutioTypeScale.tvBody).copy(fontWeight = PutioTypeScale.tvLabel.fontWeight),
        labelMedium = labelMedium.merge(PutioTypeScale.tvLabel),
        labelSmall = labelSmall.merge(PutioTypeScale.tvLabel),
    )
}

internal val putioTvShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(PutioRadius.extraSmall),
    small = RoundedCornerShape(PutioRadius.small),
    medium = RoundedCornerShape(PutioRadius.medium),
    large = RoundedCornerShape(PutioRadius.large),
    extraLarge = RoundedCornerShape(PutioRadius.extraLarge),
)
