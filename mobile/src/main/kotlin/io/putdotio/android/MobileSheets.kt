package io.putdotio.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius

@Composable
internal fun MobileSheetHandle() {
    val description = stringResource(R.string.mobile_sheet_handle_description)
    Box(
        Modifier.size(48.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 16.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(PutioRadius.progress))
                .background(PutioDesignTokens.solid),
        )
    }
}

@Composable
internal fun MobileSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(PutioRadius.small),
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(
                        onClick = data::performAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = PutioDesignTokens.yellowSolid),
                    ) { Text(label) }
                }
            },
        ) { Text(data.visuals.message) }
    }
}
