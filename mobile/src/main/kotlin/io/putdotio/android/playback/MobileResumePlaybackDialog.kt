package io.putdotio.android.playback

import android.text.format.DateUtils
import io.putdotio.android.playback.R as PlaybackR
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.R
import io.putdotio.android.neutralTextButtonColors
import io.putdotio.android.design.PutioTypeScale

@Composable
internal fun MobileResumePlaybackDialog(
    startFromSeconds: Double,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = DateUtils.formatElapsedTime(startFromSeconds.toLong())
    val title = stringResource(PlaybackR.string.playback_resume_title, RESUME_TIME_SLOT)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(RESUME_DIALOG_WIDTH).padding(horizontal = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                buildAnnotatedString {
                    append(title.substringBefore(RESUME_TIME_SLOT))
                    withStyle(
                        SpanStyle(
                            fontFamily = PutioTypeScale.mono.fontFamily,
                            fontWeight = PutioTypeScale.mono.fontWeight,
                            fontFeatureSettings = PutioTypeScale.mono.fontFeatureSettings,
                        ),
                    ) { append(time) }
                    append(title.substringAfter(RESUME_TIME_SLOT))
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(stringResource(PlaybackR.string.playback_resume)) }
        },
        dismissButton = {
            TextButton(
                onClick = onRestart,
                colors = neutralTextButtonColors(),
            ) { Text(stringResource(PlaybackR.string.playback_start_over)) }
        },
    )
}

private const val RESUME_TIME_SLOT = "\u0000"
private val RESUME_DIALOG_WIDTH = 428.dp
