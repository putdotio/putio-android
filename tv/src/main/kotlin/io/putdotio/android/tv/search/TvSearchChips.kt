package io.putdotio.android.tv.search

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.search.SearchFilter
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.paneSection

internal const val TV_SEARCH_FILTERS_TAG = "tv-search-filters"

@Composable
internal fun TvSearchChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(PutioRadius.small)
    val colors = MaterialTheme.colorScheme
    val rest = if (selected) PutioDesignTokens.componentBgActive else Color.Transparent
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val outlined = !selected && !focused && !pressed
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(TV_SEARCH_CHIP_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = rest,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .then(if (outlined) Modifier.border(CHIP_BORDER, colors.borderVariant, shape) else Modifier)
                .padding(horizontal = CHIP_PADDING),
            horizontalArrangement = Arrangement.spacedBy(CHIP_ICON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(CHIP_ICON))
            }
            Text(label, style = CHIP_LABEL, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSearchFilters(
    selected: SearchFilter,
    onSelect: (SearchFilter) -> Unit,
    onSettings: (() -> Unit)?,
    owner: TvPaneFocusOwner,
    focus: FocusRequester,
    settingsFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val selectedChip = remember { FocusRequester() }
    Row(
        modifier = modifier
            .paneSection(owner, focus)
            .focusRequester(focus)
            .focusRestorer(selectedChip)
            .focusGroup()
            .testTag(TV_SEARCH_FILTERS_TAG),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchFilter.entries.forEach { filter ->
            val chosen = filter == selected
            TvSearchChip(
                label = stringResource(filter.tvLabel()),
                onClick = { onSelect(filter) },
                icon = R.drawable.ic_ph_check.takeIf { chosen },
                selected = chosen,
                modifier = Modifier
                    .focusRequesterIf(chosen, selectedChip)
                    .semantics { this.selected = chosen },
            )
        }
        if (onSettings != null) {
            TvSearchChip(
                label = stringResource(R.string.tv_search_settings),
                onClick = onSettings,
                icon = R.drawable.ic_ph_gear_six,
                modifier = Modifier
                    .padding(start = SETTINGS_EXTRA_GAP)
                    .focusRequester(settingsFocus),
            )
        }
    }
}

@StringRes
private fun SearchFilter.tvLabel(): Int =
    when (this) {
        SearchFilter.All -> R.string.tv_search_filter_all
        SearchFilter.Folders -> R.string.tv_search_filter_folders
        SearchFilter.Videos -> R.string.tv_search_filter_videos
        SearchFilter.Audio -> R.string.tv_search_filter_audio
        SearchFilter.Subtitles -> R.string.tv_search_filter_subtitles
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvRecentSearches(
    terms: List<SearchTerm>,
    onSearch: (SearchTerm) -> Unit,
    onRemove: (SearchTerm) -> Unit,
    owner: TvPaneFocusOwner,
    modifier: Modifier = Modifier,
) {
    val firstChip = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val rowHasFocus = remember { mutableStateOf(false) }
    var focusedTerm by remember { mutableStateOf<SearchTerm?>(null) }
    val refocus = remember { mutableStateOf(false) }
    val lost = focusedTerm
    if (lost != null && lost !in terms) {
        focusedTerm = null
        if (rowHasFocus.value) refocus.value = true
    }
    LaunchedEffect(refocus.value) {
        if (!refocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (owner.owns(rowFocus)) firstChip.requestFocus()
        refocus.value = false
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.tv_search_recent_title),
            style = PutioTypeScale.tvLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = RECENT_LABEL_GAP),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .paneSection(owner, rowFocus)
                .onFocusChanged { rowHasFocus.value = it.hasFocus }
                .focusRequester(rowFocus)
                .focusRestorer(firstChip)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        ) {
            itemsIndexed(terms, key = { _, term -> term.value }) { index, term ->
                val label = stringResource(R.string.tv_search_recent_term, term.value)
                TvSearchChip(
                    label = term.value,
                    onClick = { onSearch(term) },
                    onLongClick = { onRemove(term) },
                    icon = R.drawable.ic_ph_clock_counter_clockwise,
                    modifier = Modifier
                        .focusRequesterIf(index == 0, firstChip)
                        .onFocusChanged { if (it.isFocused) focusedTerm = term }
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

internal val TV_SEARCH_CHIP_HEIGHT = 26.dp
private val CHIP_BORDER = 1.dp
private val CHIP_HORIZONTAL_INSET = 12.dp
private val CHIP_PADDING = CHIP_HORIZONTAL_INSET + CHIP_BORDER
private val CHIP_ICON = 15.dp
private val CHIP_ICON_GAP = 6.dp
private val CHIP_GAP = 8.dp
private val SETTINGS_EXTRA_GAP = 8.dp
private val RECENT_LABEL_GAP = 8.dp
private val CHIP_LABEL = PutioTypeScale.tvButton.copy(fontSize = 11.sp, lineHeight = 16.sp)
