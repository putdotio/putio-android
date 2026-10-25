package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioMono

/**
 * A list's multi-selection by row ID. Select can open it empty; a row that leaves the list leaves
 * the selection too, and an empty list ends it.
 */
@Stable
internal class MobileSelection(selecting: Boolean = false, selected: List<Long> = emptyList()) {
    private var selecting by mutableStateOf(selecting)
    private var selectedIds by mutableStateOf(selected)

    /** The selected rows still in [selectable], in the order they were selected. */
    fun selected(selectable: Set<Long>): Set<Long> = selectedIds.filterTo(linkedSetOf()) { it in selectable }

    fun isActive(selectable: Set<Long>): Boolean =
        (selecting || selected(selectable).isNotEmpty()) && selectable.isNotEmpty()

    fun start() {
        selecting = true
    }

    fun toggle(id: Long) {
        selecting = true
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll(selectable: Collection<Long>) {
        selecting = true
        selectedIds = selectable.toList()
    }

    fun end() {
        selecting = false
        selectedIds = emptyList()
    }

    companion object {
        val Saver: Saver<MobileSelection, Any> = Saver(
            save = { listOf(it.selecting, it.selectedIds.toLongArray()) },
            restore = { saved ->
                val values = saved as List<*>
                MobileSelection(values[0] as Boolean, (values[1] as LongArray).toList())
            },
        )
    }
}

/**
 * The bar that replaces a list's top bar in selection: close, the count in mono and select all, on
 * `surface-container-high`. The status bar keeps the page colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileSelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
        TopAppBar(
            title = { Text(mobileSelectedCount(count)) },
            navigationIcon = {
                IconButton(onClick = onClear) {
                    Icon(
                        painterResource(R.drawable.ic_ph_x),
                        contentDescription = stringResource(R.string.mobile_selection_clear),
                    )
                }
            },
            actions = {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        painterResource(R.drawable.ic_ph_checks),
                        contentDescription = stringResource(R.string.mobile_selection_select_all),
                    )
                }
            },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** "3 selected", its count in mono. */
@Composable
private fun mobileSelectedCount(count: Int): AnnotatedString {
    val text = pluralStringResource(R.plurals.mobile_selection_count, count, count)
    val number = count.toString()
    val at = text.indexOf(number)
    val mono = MaterialTheme.typography.titleLarge.putioMono().toSpanStyle()
    return if (at < 0) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text.substring(0, at))
            withStyle(mono) { append(number) }
            append(text.substring(at + number.length))
        }
    }
}

/** A selectable row's checkbox in place of its leading glyph: yellow checked, `solid` outline unchecked. */
@Composable
internal fun MobileSelectionCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    Checkbox(
        checked = checked,
        onCheckedChange = null,
        colors = CheckboxDefaults.colors(uncheckedColor = PutioDesignTokens.solid),
        modifier = modifier,
    )
}

/** A selected row's 9% yellow tint. */
internal val MobileSelectedRowTint: Color = PutioDesignTokens.yellowSolid.copy(alpha = 0.09f)

/**
 * The selection's floating toolbar: Material 3's floating toolbar is still internal here, so this
 * is its tonal container, without a shadow, holding [MobileToolbarTile]s.
 */
@Composable
internal fun MobileSelectionToolbar(modifier: Modifier = Modifier, tiles: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(TOOLBAR_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(TOOLBAR_PADDING),
            horizontalArrangement = Arrangement.spacedBy(TOOLBAR_GAP),
            content = tiles,
        )
    }
}

/** An icon over a label-md word, in a target of at least 64x52dp. */
@Composable
internal fun MobileToolbarTile(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    color: Color = LocalContentColor.current,
    enabled: Boolean = true,
) {
    val tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Column(
        modifier = modifier
            // At least 64x52dp; a large font grows the tile instead of clipping its word.
            .defaultMinSize(TILE_WIDTH, TILE_HEIGHT)
            .clip(RoundedCornerShape(TILE_CORNER))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(TILE_ICON))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** How far the floating toolbar sits above the bottom of its list. */
internal val MobileSelectionToolbarOffset = 8.dp

/** Space under the toolbar's own height, so a list's last row ends above it with a gap. */
internal val MobileSelectionToolbarClearance = MobileSelectionToolbarOffset + 16.dp

private const val DISABLED_ALPHA = 0.38f
private val TOOLBAR_PADDING = 6.dp
private val TOOLBAR_GAP = 4.dp
private val TOOLBAR_CORNER = 28.dp
private val TILE_WIDTH = 64.dp
private val TILE_HEIGHT = 52.dp
private val TILE_CORNER = 22.dp
private val TILE_ICON = 22.dp
