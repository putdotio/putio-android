package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.putdotio.android.FilesFailure
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph

@Composable
internal fun TvListSkeleton(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.tv_state_loading)
    Column(
        modifier = modifier
            .offset(y = -SKELETON_LIFT)
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = loading },
    ) {
        SKELETON_NAME_WIDTHS.forEach { nameWidth ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TV_LIST_ROW_HEIGHT)
                    .padding(horizontal = SKELETON_ROW_PADDING),
                horizontalArrangement = Arrangement.spacedBy(SKELETON_ROW_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(SKELETON_GLYPH, SKELETON_GLYPH, shape = RoundedCornerShape(SKELETON_GLYPH_RADIUS))
                Column {
                    SkeletonBlock(nameWidth, SKELETON_NAME_HEIGHT)
                    SkeletonBlock(
                        SKELETON_META_WIDTH,
                        SKELETON_META_HEIGHT,
                        modifier = Modifier.padding(top = SKELETON_META_GAP),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TvDetailSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics {}) {
        SkeletonBlock(SKELETON_DETAIL_TITLE_WIDTH, SKELETON_DETAIL_TITLE_HEIGHT)
        SkeletonBlock(
            SKELETON_DETAIL_META_WIDTH,
            SKELETON_DETAIL_META_HEIGHT,
            modifier = Modifier.padding(top = SKELETON_DETAIL_META_GAP),
        )
        Row(
            modifier = Modifier.padding(top = SKELETON_DETAIL_ACTIONS_GAP),
            horizontalArrangement = Arrangement.spacedBy(SKELETON_DETAIL_ACTION_SPACING),
        ) {
            SkeletonBlock(SKELETON_DETAIL_PRIMARY_WIDTH, TvButtonSize.Detail.height, shape = CircleShape)
            SkeletonBlock(SKELETON_DETAIL_SECONDARY_WIDTH, TvButtonSize.Detail.height, shape = CircleShape)
        }
    }
}

@Composable
internal fun TvLoadFailedState(
    failure: FilesFailure,
    headline: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionFocus: FocusRequester = remember { FocusRequester() },
    claimFocus: Boolean = true,
) {
    val offline = failure is PutioFailure.NetworkUnavailable
    TvEmptyState(
        glyph = if (offline) PutioEmptyGlyph.Offline else PutioEmptyGlyph.CouldNotLoad,
        headline = if (offline) stringResource(R.string.tv_state_offline_title) else headline,
        message = message,
        action = TvEmptyStateAction(
            label = stringResource(if (offline) R.string.tv_state_try_now else R.string.tv_state_try_again),
            onClick = onRetry,
            icon = R.drawable.ic_ph_arrows_clockwise,
        ),
        modifier = modifier,
        actionFocus = actionFocus,
        claimFocus = claimFocus,
    )
}

@Composable
private fun SkeletonBlock(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SKELETON_RADIUS),
) {
    Box(modifier.size(width, height).background(PutioDesignTokens.componentBgHover, shape))
}

private val SKELETON_LIFT = 2.dp
private val SKELETON_NAME_WIDTHS = listOf(210.dp, 150.dp, 260.dp, 180.dp, 230.dp, 140.dp, 200.dp)
private val SKELETON_ROW_PADDING = ROW_BLEED
private val SKELETON_ROW_GAP = 16.dp
private val SKELETON_GLYPH = 20.dp
private val SKELETON_GLYPH_RADIUS = 5.dp
private val SKELETON_RADIUS = 6.dp
private val SKELETON_NAME_HEIGHT = 12.dp
private val SKELETON_META_WIDTH = 90.dp
private val SKELETON_META_HEIGHT = 9.dp
private val SKELETON_META_GAP = 5.dp
private val SKELETON_DETAIL_TITLE_WIDTH = 210.dp
private val SKELETON_DETAIL_TITLE_HEIGHT = 18.dp
private val SKELETON_DETAIL_META_WIDTH = 120.dp
private val SKELETON_DETAIL_META_HEIGHT = 11.dp
private val SKELETON_DETAIL_META_GAP = 8.dp
private val SKELETON_DETAIL_ACTIONS_GAP = 16.dp
private val SKELETON_DETAIL_ACTION_SPACING = 6.dp
private val SKELETON_DETAIL_PRIMARY_WIDTH = 120.dp
private val SKELETON_DETAIL_SECONDARY_WIDTH = 80.dp
