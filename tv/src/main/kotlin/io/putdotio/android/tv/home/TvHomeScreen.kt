package io.putdotio.android.tv.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonSize
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvEmptyStateAction
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.asMonoRun
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.files.tvClock
import io.putdotio.android.tv.files.tvMessageText
import io.putdotio.android.tv.tvAccent
import io.putdotio.android.tv.withFullLineBox
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType

internal const val TV_HOME_CARD_TAG = "tv-home-card"

internal class TvHomeActions(
    val onResume: (ContinueWatchingEntry) -> Unit,
    val onOpen: (FilesItem) -> Unit,
    val onBrowseFiles: () -> Unit,
    val onRetryPinned: () -> Unit,
)

@Composable
internal fun TvHomeScreen(
    continueWatching: List<ContinueWatchingEntry>,
    pinned: TvPinnedRow,
    actions: TvHomeActions,
    paneFocus: FocusRequester,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
    returnTo: FilesItemId? = null,
    onReturned: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onShown() }
    val pinnedCards = (pinned as? TvPinnedRow.Ready)?.cards
    val continueRow = rememberLazyListState()
    val pinnedRow = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    val continueReturn = continueWatching.indexOfFirst { it.fileId == returnTo }
    val pinnedReturn = if (continueReturn >= 0) -1 else pinnedCards.orEmpty().indexOfFirst { it.item.id == returnTo }
    LaunchedEffect(returnTo) {
        val id = returnTo ?: return@LaunchedEffect
        val row = when {
            continueReturn >= 0 -> continueRow to continueReturn
            pinnedReturn >= 0 -> pinnedRow to pinnedReturn
            else -> null
        }
        row?.let { (state, index) ->
            state.bringIntoComposition(id.value, index)
            withFrameNanos {}
            returnFocus.requestFocus()
        }
        onReturned()
    }
    if (continueWatching.isEmpty() && pinnedCards?.isEmpty() == true) {
        TvEmptyState(
            glyph = PutioEmptyGlyph.NothingYet,
            headline = stringResource(R.string.tv_home_first_run_title),
            message = stringResource(R.string.tv_home_first_run_message),
            action = TvEmptyStateAction(
                label = stringResource(R.string.tv_home_browse_files),
                onClick = actions.onBrowseFiles,
                icon = R.drawable.ic_ph_folder,
            ),
            actionFocus = paneFocus,
            claimFocus = false,
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = HOME_TOP)
            .focusRequester(paneFocus)
            .focusRestorer()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        if (continueWatching.isNotEmpty()) {
            TvHomeSection(stringResource(R.string.tv_home_continue_watching)) {
                LazyRow(state = continueRow, horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    itemsIndexed(continueWatching, key = { _, entry -> entry.fileId.value }) { index, entry ->
                        TvContinueWatchingCard(
                            entry,
                            onClick = { actions.onResume(entry) },
                            modifier = Modifier.focusRequesterIf(index == continueReturn, returnFocus),
                        )
                    }
                }
            }
        }
        when (pinned) {
            TvPinnedRow.Loading -> Unit
            is TvPinnedRow.Failed -> TvHomeSection(stringResource(R.string.tv_home_pinned), cardsGap = EMPTY_GAP) {
                TvHomeInlineState(
                    glyph = PutioEmptyGlyph.CouldNotLoad.text,
                    headline = stringResource(R.string.tv_home_pinned_error_title),
                    message = pinned.failure.tvMessageText(),
                    retry = actions.onRetryPinned,
                )
            }
            is TvPinnedRow.Ready -> if (pinned.cards.isEmpty()) {
                TvHomeSection(stringResource(R.string.tv_home_pinned), cardsGap = EMPTY_GAP) {
                    TvHomeInlineState(
                        glyph = PutioEmptyGlyph.NothingYet.text,
                        headline = stringResource(R.string.tv_home_pinned_empty_title),
                        message = stringResource(R.string.tv_home_pinned_empty_message),
                    )
                }
            } else {
                TvHomeSection(stringResource(R.string.tv_home_pinned)) {
                    LazyRow(state = pinnedRow, horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                        itemsIndexed(pinned.cards, key = { _, card -> card.item.id.value }) { index, card ->
                            TvPinnedFileCard(
                                card,
                                onClick = { actions.onOpen(card.item) },
                                modifier = Modifier.focusRequesterIf(index == pinnedReturn, returnFocus),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHomeSection(
    label: String,
    cardsGap: Dp = LABEL_GAP,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = label,
            style = SECTION_LABEL,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(cardsGap))
        content()
    }
}

@Composable
private fun TvHomeInlineState(glyph: String, headline: String, message: String, retry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(INLINE_STATE_GAP)) {
        Text(
            glyph,
            style = INLINE_GLYPH,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(headline, style = INLINE_HEADLINE, color = MaterialTheme.colorScheme.onSurface)
        Text(
            message,
            style = INLINE_MESSAGE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = INLINE_MESSAGE_NUDGE),
        )
        if (retry != null) {
            TvButton(onClick = retry, modifier = Modifier.padding(top = INLINE_ACTION_GAP)) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_arrow_clockwise),
                    contentDescription = null,
                    modifier = Modifier.size(TvButtonSize.Regular.icon),
                )
                Text(
                    stringResource(R.string.tv_files_retry),
                    style = PutioTypeScale.bodySm.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun TvContinueWatchingCard(entry: ContinueWatchingEntry, onClick: () -> Unit, modifier: Modifier) {
    val left = tvClock(entry.remainingSeconds.toDouble())
    val leftLabel = stringResource(R.string.tv_home_left, left)
    TvHomeCard(
        icon = fileTypeIconRes(PutioFileType.VIDEO),
        name = entry.name,
        description = stringResource(R.string.tv_home_resume_description, entry.name, left),
        progress = entry.fraction,
        meta = AnnotatedString(leftLabel),
        metaStyle = CARD_META_MONO,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun TvPinnedFileCard(card: TvPinnedCard, onClick: () -> Unit, modifier: Modifier) {
    val meta = pinnedMeta(card)
    TvHomeCard(
        icon = if (card.item.isSharedRoot) R.drawable.ic_ph_users_three_fill else fileTypeIconRes(card.item.type),
        name = card.item.name,
        description = card.item.name,
        progress = null,
        meta = meta,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun pinnedMeta(card: TvPinnedCard): AnnotatedString {
    val items = card.itemCount
    return when {
        items != null && card.item.isSharedRoot ->
            AnnotatedString(pluralStringResource(R.plurals.tv_home_shared_friends, items, items.toString()))
        items != null && items > 0 && card.folderCount == items ->
            AnnotatedString(pluralStringResource(R.plurals.tv_home_folder_count, items, items.toString()))
        else -> itemMeta(card.item, items)
    }
}

@Composable
private fun itemMeta(item: FilesItem, items: Int?): AnnotatedString {
    val context = LocalContext.current
    val date = remember(item.createdAt, context) { item.createdAt.toDisplayDate(context) }
    val count = items?.let { pluralStringResource(R.plurals.tv_files_item_count, it, it.toString()) }
    val parts = buildList {
        count?.let { add(AnnotatedString(it)) }
        add(formatFileSize(item.sizeBytes).asMonoRun())
        if (!item.isFolder) date?.let { add(AnnotatedString(it)) }
    }
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(META_SEPARATOR)
            append(part)
        }
    }
}

@Composable
private fun TvHomeCard(
    @DrawableRes icon: Int,
    name: String,
    description: String,
    progress: Float?,
    meta: AnnotatedString,
    onClick: () -> Unit,
    modifier: Modifier,
    metaStyle: TextStyle = CARD_META,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .semantics { contentDescription = description }
            .testTag(TV_HOME_CARD_TAG),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(CARD_RADIUS)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surfaceVariant,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Column(modifier = Modifier.padding(horizontal = CARD_PADDING_H, vertical = CARD_PADDING_V)) {
            Row(
                modifier = Modifier.height(CARD_GLYPH),
                horizontalArrangement = Arrangement.spacedBy(CARD_NAME_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = tvAccent(focused),
                    modifier = Modifier.size(CARD_GLYPH),
                )
                Text(name, style = CARD_NAME, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(CARD_INNER_GAP))
            TvHomeProgress(progress, focused)
            Spacer(Modifier.height(CARD_INNER_GAP))
            Text(
                meta,
                style = metaStyle,
                color = if (focused) TvFocus.secondaryContent else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvHomeProgress(progress: Float?, focused: Boolean) {
    if (progress == null) {
        Spacer(Modifier.height(PROGRESS_HEIGHT))
        return
    }
    val shape = RoundedCornerShape(PutioRadius.progress)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .clip(shape)
            .background(if (focused) FocusedTrack else PutioDesignTokens.componentBgActive),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .clip(shape)
                .background(if (focused) TvFocus.content else PutioDesignTokens.yellowSolid),
        )
    }
}

private val FocusedTrack = Color(FOCUSED_TRACK)
private const val FOCUSED_TRACK = 0x24000000
private val HOME_TOP = 4.dp
private val SECTION_GAP = 21.dp
private val LABEL_GAP = 9.dp
private val EMPTY_GAP = 14.5.dp
private val CARD_GAP = 8.dp
private val CARD_WIDTH = 200.dp
private val CARD_HEIGHT = 72.dp
private val CARD_RADIUS = 14.dp
private val CARD_PADDING_H = 11.dp
private val CARD_PADDING_V = 10.dp
private val CARD_GLYPH = 17.dp
private val CARD_NAME_GAP = 8.dp
private val CARD_INNER_GAP = 7.dp
private val PROGRESS_HEIGHT = 3.dp
private val INLINE_STATE_GAP = 8.dp
private val INLINE_MESSAGE_NUDGE = 1.5.dp
private val INLINE_ACTION_GAP = 10.dp
private val SECTION_LABEL = PutioTypeScale.tvLabel.withFullLineBox()
private val CARD_NAME = PutioTypeScale.tvMeta.withFullLineBox()
private val CARD_META = PutioTypeScale.tvMeta.copy(lineHeight = 18.sp).withFullLineBox()
private val CARD_META_MONO = PutioTypeScale.tvMono.copy(lineHeight = 18.sp).withFullLineBox()
private val INLINE_GLYPH = PutioTypeScale.tvMono.copy(fontSize = 24.sp, lineHeight = 28.sp).withFullLineBox()
private val INLINE_HEADLINE =
    PutioTypeScale.tvDetailTitle.copy(fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
        .withFullLineBox()
private val INLINE_MESSAGE = PutioTypeScale.tvMeta.copy(lineHeight = 15.sp).withFullLineBox()
private const val META_SEPARATOR = " · "

private val FilesItem.isSharedRoot: Boolean
    get() = folderType == PutioFolderType.SHARED_ROOT
