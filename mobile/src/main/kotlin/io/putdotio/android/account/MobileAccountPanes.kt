package io.putdotio.android.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyState
import io.putdotio.android.files.MobileFilesListDetail
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINKS_ROUTE
import io.putdotio.android.trash.MOBILE_TRASH_ROUTE

internal const val MOBILE_ACCOUNT_PAGE_PANE_TAG = "mobile-account-page-pane"
internal const val MOBILE_ACCOUNT_NO_PAGE_TAG = "mobile-account-no-page"

internal enum class MobileAccountPage(val route: String, @StringRes val title: Int) {
    ExclusiveLinks(MOBILE_PUBLIC_LINKS_ROUTE, R.string.mobile_settings_exclusive_links),
    Trash(MOBILE_TRASH_ROUTE, R.string.mobile_trash_title),
    PrivacyControls(MOBILE_PRIVACY_CONTROLS_ROUTE, R.string.mobile_settings_privacy_controls),
    LoggedInApps(MOBILE_LOGGED_IN_APPS_ROUTE, R.string.mobile_settings_logged_in_apps),
    AppInfo(MOBILE_APP_INFO_ROUTE, R.string.mobile_settings_about),
    OpenSourceLicenses(MOBILE_OPEN_SOURCE_LICENSES_ROUTE, R.string.mobile_settings_open_source_licenses),
    ;

    companion object {
        fun ofRoute(route: String?): MobileAccountPage? = entries.firstOrNull { it.route == route }
    }
}

internal class MobileAccountPanes(
    val listTopBar: @Composable () -> Unit,
    val pageTopBar: @Composable (MobileAccountPage) -> Unit,
)

@Composable
internal fun MobileAccountListDetail(
    panes: MobileAccountPanes?,
    open: MobileAccountPage?,
    list: @Composable (open: MobileAccountPage?) -> Unit,
    page: @Composable () -> Unit,
) {
    when {
        panes != null -> MobileFilesListDetail(
            topBar = panes.listTopBar,
            list = { list(open) },
            detail = {
                if (open == null) {
                    MobileAccountNoPage()
                } else {
                    Column(Modifier.fillMaxSize().testTag(MOBILE_ACCOUNT_PAGE_PANE_TAG)) {
                        panes.pageTopBar(open)
                        Box(Modifier.weight(1f)) { page() }
                    }
                }
            },
        )
        open == null -> list(null)
        else -> page()
    }
}

@Composable
private fun MobileAccountNoPage() {
    Box(Modifier.fillMaxSize().testTag(MOBILE_ACCOUNT_NO_PAGE_TAG)) {
        PutioEmptyState(
            glyph = null,
            headline = stringResource(R.string.mobile_account_no_page_title),
            message = stringResource(R.string.mobile_account_no_page_message),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
        )
    }
}

@Stable
internal class MobileAccountListScroll(index: Int, offset: Int) {
    var index by mutableIntStateOf(index)
        private set
    var offset by mutableIntStateOf(offset)
        private set

    @Composable
    fun rememberListState(): LazyListState {
        val state = remember { LazyListState(index, offset) }
        LaunchedEffect(state) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (firstIndex, firstOffset) ->
                    index = firstIndex
                    offset = firstOffset
                }
        }
        return state
    }
}

@Composable
internal fun rememberAccountListScroll(): MobileAccountListScroll =
    rememberSaveable(
        saver = listSaver(
            save = { listOf(it.index, it.offset) },
            restore = { (index, offset) -> MobileAccountListScroll(index, offset) },
        ),
    ) { MobileAccountListScroll(0, 0) }
