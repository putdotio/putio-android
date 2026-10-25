package io.putdotio.android.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val MOBILE_FILES_LIST_PANE_TAG = "mobile-files-list-pane"

/** Files shows list and detail side by side in a window 840dp or wider. */
internal fun isFilesListDetailWidth(width: Dp): Boolean = width >= FILES_LIST_DETAIL_MIN_WIDTH

/** Whether Files' bar sits over its list pane: the panes are on screen, not under Search or the player. */
internal fun showsFilesListDetail(width: Dp, onFiles: Boolean, searchOpen: Boolean, isPlayback: Boolean): Boolean =
    isFilesListDetailWidth(width) && onFiles && !searchOpen && !isPlayback

private val FILES_LIST_DETAIL_MIN_WIDTH: Dp = 840.dp

/**
 * Files as Material's list-detail: the folder's bar and listing on the left, at most 520dp and never
 * more than half the width, and the selected file's detail filling the rest. Both panes always show,
 * so the scaffold never navigates between them; Back stays with the folder stack.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun MobileFilesListDetail(
    topBar: @Composable () -> Unit,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = minOf(FILES_LIST_PANE_WIDTH, maxWidth / 2)
        val directive = remember(listWidth) {
            PaneScaffoldDirective(
                maxHorizontalPartitions = 2,
                horizontalPartitionSpacerSize = 0.dp,
                maxVerticalPartitions = 1,
                verticalPartitionSpacerSize = 0.dp,
                defaultPanePreferredWidth = listWidth,
                excludedBounds = emptyList(),
            )
        }
        ListDetailPaneScaffold(
            directive = directive,
            value = BOTH_PANES,
            listPane = {
                AnimatedPane(Modifier.preferredWidth(listWidth)) {
                    Row(Modifier.fillMaxSize().testTag(MOBILE_FILES_LIST_PANE_TAG)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            topBar()
                            Box(Modifier.weight(1f)) { list() }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            },
            detailPane = { AnimatedPane { detail() } },
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val BOTH_PANES = ThreePaneScaffoldValue(
    primary = PaneAdaptedValue.Expanded,
    secondary = PaneAdaptedValue.Expanded,
    tertiary = PaneAdaptedValue.Hidden,
)

private val FILES_LIST_PANE_WIDTH = 520.dp
