package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal const val MOBILE_NAV_MENU_TAG = "mobile-navigation-menu"
internal const val MOBILE_NAV_DRAWER_TAG = "mobile-navigation-drawer"

internal enum class MobileNavigationLayout { Bar, Rail, Modal }

@Composable
internal fun mobileNavigationLayout(width: Dp, height: Dp): MobileNavigationLayout {
    val density = LocalDensity.current
    // IME changes must not reparent the shell and dispose a focused editor.
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val direction = LocalLayoutDirection.current
    val availableWidth = width - with(density) {
        (insets.getLeft(density, direction) + insets.getRight(density, direction)).toDp()
    }
    val availableHeight = height - with(density) { (insets.getTop(density) + insets.getBottom(density)).toDp() }
    val measurer = rememberTextMeasurer()
    val labels = MobileDestination.entries.map {
        measurer.measure(stringResource(it.labelRes), MaterialTheme.typography.labelMedium, softWrap = false).size
    }
    val widest = with(density) { labels.maxOf { it.width }.toDp() }
    val tallest = with(density) { labels.maxOf { it.height }.toDp() }
    // Reserve the stock components' icon, label spacing and horizontal padding.
    return if (width >= 600.dp) {
        val railHeight = (24.dp + 12.dp + tallest + 24.dp) * MobileDestination.entries.size + 16.dp
        if (widest <= 64.dp && availableHeight >= railHeight) {
            MobileNavigationLayout.Rail
        } else {
            MobileNavigationLayout.Modal
        }
    } else {
        val labelWidth = availableWidth / MobileDestination.entries.size - 24.dp
        val minimumViewport = 64.dp + (24.dp + tallest + 32.dp) * 3
        if (widest <= labelWidth && availableHeight >= minimumViewport) {
            MobileNavigationLayout.Bar
        } else {
            MobileNavigationLayout.Modal
        }
    }
}

@Composable
internal fun MobileNavigationContainer(
    layout: MobileNavigationLayout,
    selectedDestination: MobileDestination,
    onDestination: (MobileDestination) -> Unit,
    content: @Composable ((() -> Unit)?) -> Unit,
) {
    if (layout != MobileNavigationLayout.Modal) {
        content(null)
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val visible = drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open
    val close: () -> Unit = { scope.launch(start = CoroutineStart.UNDISPATCHED) { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = visible,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.testTag(MOBILE_NAV_DRAWER_TAG)) {
                Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    TextButton(onClick = close) { Text(stringResource(R.string.mobile_navigation_close)) }
                    MobileDestination.entries.forEach { destination ->
                        val selected = destination == selectedDestination
                        NavigationDrawerItem(
                            selected = selected,
                            onClick = {
                                onDestination(destination)
                                close()
                            },
                            icon = {
                                Icon(
                                    painterResource(if (selected) destination.selectedIcon else destination.icon),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) {
        content { scope.launch(start = CoroutineStart.UNDISPATCHED) { drawer.open() } }
    }
    // Register with the overlay so NavHost callbacks added after route changes remain underneath it.
    if (visible) BackHandler(onBack = close)
}

@Composable
internal fun MobileNavigationMenuButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(MOBILE_NAV_MENU_TAG)) {
        Text(stringResource(R.string.mobile_navigation_menu))
    }
}
