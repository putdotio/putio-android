package io.putdotio.android.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography

/**
 * DESIGN.md › Android TV on Compose for TV: the generated TV scheme, the `tv-*` type roles and the
 * shared corner scale. Dark only, like the phone.
 */
@Composable
public fun PutioTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = putioTvDarkColorScheme(),
        typography = putioTvTypography,
        shapes = putioTvShapes,
        content = content,
    )
}

/**
 * The `tv-*` roles on the slots Compose for TV components read: a ListItem's headline reads
 * titleMedium (titleSmall when dense) and its supporting line bodySmall, buttons and chips read
 * labelLarge, and overlines labelSmall. Rows, tabs, buttons and dialog bodies are `tv-body`; sheet
 * and dialog titles `tv-title`; the detail title and empty-state headlines `tv-detail-title`. A
 * button's label keeps `tv-body`'s size at the label weight. Display slots keep Compose for TV's
 * sizes in GT America, so no slot falls back to Roboto.
 */
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

/** DESIGN.md's corners on Compose for TV's shape scale. */
internal val putioTvShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(PutioRadius.extraSmall),
    small = RoundedCornerShape(PutioRadius.small),
    medium = RoundedCornerShape(PutioRadius.medium),
    large = RoundedCornerShape(PutioRadius.large),
    extraLarge = RoundedCornerShape(PutioRadius.extraLarge),
)
