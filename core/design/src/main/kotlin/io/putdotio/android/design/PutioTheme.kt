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

/**
 * The tier-2 binding from DESIGN.md: one generated dark color scheme, the contract's type and
 * corner scales, and stock Material 3 components. Dark only; there is no light scheme in the
 * contract.
 */
@Composable
public fun PutioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = putioDarkColorScheme(),
        typography = putioTypography,
        shapes = putioShapes,
        content = content,
    )
}

/**
 * DESIGN.md's type scale on the Material slots stock components read: the top app bar reads
 * titleLarge, dialog titles headlineSmall, list headlines bodyLarge, dialog, sheet and snackbar
 * bodies bodyMedium, and nav labels labelMedium. Slots the contract does not name keep Material's
 * values, except labelSmall, which rises to label-sm so no public slot sets text below 12sp.
 * Material 3 1.4 keeps its emphasized slots internal, and no stock component reads
 * labelSmallEmphasized.
 */
internal val putioTypography: Typography = Typography().run {
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

/** DESIGN.md's corners on Material's shape scale; medium, which the contract leaves out, stays 12dp. */
internal val putioShapes: Shapes = Shapes().copy(
    extraSmall = RoundedCornerShape(PutioRadius.extraSmall),
    small = RoundedCornerShape(PutioRadius.small),
    large = RoundedCornerShape(PutioRadius.large),
    extraLarge = RoundedCornerShape(PutioRadius.extraLarge),
)

/** Numbers never go below 12sp (DESIGN.md Typography). */
private val MINIMUM_NUMBER_SIZE = 12.sp

/**
 * This style, over the inherited text style, with numbers set in the mono face and tabular
 * figures. Mono takes the size of the text it sits in, raised to 12sp when that is smaller.
 */
@Composable
@ReadOnlyComposable
public fun TextStyle.putioMono(): TextStyle {
    val inherited = LocalTextStyle.current
    return monoNumbers(inherited.merge(this), inherited.fontSize)
}

/** [style] in mono, with an `em` size resolved against [inheritedSize] before the 12sp floor applies. */
internal fun monoNumbers(style: TextStyle, inheritedSize: TextUnit = TextUnit.Unspecified): TextStyle {
    val size = if (style.fontSize.isEm && inheritedSize.isSp) {
        (inheritedSize.value * style.fontSize.value).sp
    } else {
        style.fontSize
    }
    val belowFloor = size.isSp && size < MINIMUM_NUMBER_SIZE
    return style.copy(
        fontFamily = PutioTypeScale.mono.fontFamily,
        fontFeatureSettings = PutioTypeScale.mono.fontFeatureSettings,
        fontSize = if (belowFloor) MINIMUM_NUMBER_SIZE else size,
    )
}
