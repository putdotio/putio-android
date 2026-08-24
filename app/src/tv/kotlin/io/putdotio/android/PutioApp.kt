package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioTvDarkColorScheme

/**
 * TV shell: Compose for TV, list-first, per putio-design
 * platforms/android/DESIGN.md §Android TV. Focus behavior is the platform
 * engine's scale + elevate from tv-material — no tilt, no parallax, no
 * poster wall.
 *
 * Metrics use the tv token group's px values halved: the tv_1080p profile
 * renders 1920x1080 at xhdpi, so 1dp here is 2px on the card's canvas
 * (80px side padding -> 40dp, 64px top -> 32dp, 42px glyph -> 21dp).
 */
private enum class TvDestination(
    val label: String,
    val subline: String,
    @DrawableRes val icon: Int,
) {
    Files("Files", "Browse your cloud storage", R.drawable.ic_ph_folder_fill),
    Transfers("Transfers", "Watch downloads land", R.drawable.ic_ph_arrow_circle_down_fill),
    Settings("Settings", "Playback and account", R.drawable.ic_ph_gear_fill),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PutioApp() {
    MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 40.dp)
                .padding(top = 32.dp),
        ) {
            Text(
                text = "put.io",
                style = MaterialTheme.typography.headlineMedium,
                color = PutioDesignTokens.yellowSolid,
            )
            Spacer(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.borderVariant),
            )
            Column(
                modifier = Modifier.padding(top = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvDestination.entries.forEach { destination ->
                    ListItem(
                        selected = false,
                        onClick = {},
                        headlineContent = { Text(destination.label) },
                        supportingContent = { Text(destination.subline) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(destination.icon),
                                contentDescription = null,
                                tint = PutioDesignTokens.yellowSolid,
                                modifier = Modifier.size(21.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
