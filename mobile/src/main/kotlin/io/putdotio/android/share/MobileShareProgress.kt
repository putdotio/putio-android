package io.putdotio.android.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.MobileDialogDismissButton
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.sharing.styledTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.window.DialogProperties

internal const val MOBILE_SHARE_PROGRESS_TAG = "mobile-share-progress"

/** A running share export: its name, and its bytes once the download reports a size. */
internal data class MobileShareProgressState(
    val name: String,
    val copiedBytes: Long = 0L,
    val totalBytes: Long? = null,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0L }?.let { (copiedBytes * EXPORT_PROGRESS_MAX / it).toInt() }
}

/** The export the share service runs now, for the in-app dialog; null when none runs. */
internal object MobileShareProgress {
    private val mutableState = MutableStateFlow<MobileShareProgressState?>(null)
    val state: StateFlow<MobileShareProgressState?> = mutableState.asStateFlow()

    // Cancel reaches the service asynchronously; until the export ends, its progress stays hidden.
    // Progress arrives on IO and Cancel on Main, so the check and the write happen under one lock.
    private val lock = Any()
    private var dismissed = false

    fun start(name: String) = synchronized(lock) {
        dismissed = false
        mutableState.value = MobileShareProgressState(name)
    }

    fun progress(progress: MobileShareProgressState) = synchronized(lock) {
        if (!dismissed) mutableState.value = progress
    }

    fun dismiss() = synchronized(lock) {
        dismissed = true
        mutableState.value = null
    }

    fun end() = synchronized(lock) {
        mutableState.value = null
    }
}

/**
 * While a file downloads to be shared: its name, a bar with the progress in mono, and Cancel. The
 * dialog leaves when the share sheet opens or the export ends; a failure keeps its notification.
 */
@Composable
internal fun MobileShareProgressDialog(onCancel: () -> Unit) {
    val progress by MobileShareProgress.state.collectAsStateWithLifecycle()
    val shown = progress ?: return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).testTag(MOBILE_SHARE_PROGRESS_TAG),
        title = { Text(stringResource(R.string.mobile_share_progress_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val nameStyle = SpanStyle(MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text(
                    styledTemplate(
                        stringResource(R.string.mobile_share_progress_message),
                        listOf(shown.name to nameStyle),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val bar = Modifier.padding(top = 18.dp).fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(PutioRadius.progress))
                val percent = shown.percent
                if (percent == null) {
                    LinearProgressIndicator(
                        modifier = bar,
                        color = PutioDesignTokens.yellowSolid,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Butt,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { percent / EXPORT_PROGRESS_MAX.toFloat() },
                        modifier = bar,
                        color = PutioDesignTokens.yellowSolid,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Butt,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Text(
                        text = stringResource(
                            R.string.mobile_share_progress_bytes,
                            percent,
                            formatFileSize(shown.copiedBytes),
                            formatFileSize(shown.totalBytes ?: 0L),
                        ),
                        style = MaterialTheme.typography.bodySmall.putioMono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            MobileDialogDismissButton(onClick = {
                onCancel()
                MobileShareProgress.dismiss()
            })
        },
    )
}
