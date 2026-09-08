package io.putdotio.android

import android.text.format.DateUtils
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

@Composable
internal fun MobileResumePlaybackDialog(
    title: String,
    startFromSeconds: Double,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.mobile_playback_resume_title,
                    DateUtils.formatElapsedTime(startFromSeconds.toLong()),
                ),
            )
        },
        text = { Text(title, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onResume) { Text(stringResource(R.string.mobile_playback_resume)) }
        },
        dismissButton = {
            TextButton(onClick = onRestart) { Text(stringResource(R.string.mobile_playback_restart)) }
        },
    )
}
