package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.putdotio.android.design.PutioTheme

/**
 * Mobile shell per the android-s00-shell card: stock Material 3 scaffold,
 * navigation bar with the four destinations, pill indicator and colors all
 * decided by the generated scheme. Destination content arrives with the
 * auth, files, and player slices.
 */
private enum class Destination(
    val label: String,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Files("Files", R.drawable.ic_ph_folder, R.drawable.ic_ph_folder_fill),
    Transfers("Transfers", R.drawable.ic_ph_arrow_circle_down, R.drawable.ic_ph_arrow_circle_down_fill),
    Activity("Activity", R.drawable.ic_ph_clock_counter_clockwise, R.drawable.ic_ph_clock_counter_clockwise_fill),
    Account("Account", R.drawable.ic_ph_user_circle, R.drawable.ic_ph_user_circle_fill),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PutioApp() {
    PutioTheme {
        var selected by rememberSaveable { mutableStateOf(Destination.Files) }

        Scaffold(
            topBar = { TopAppBar(title = { Text(selected.label) }) },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == selected,
                            onClick = { selected = destination },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (destination == selected) destination.selectedIcon else destination.icon,
                                    ),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "put.io",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
