package io.putdotio.android.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonSize
import io.putdotio.sdk.files.PutioFileType

internal const val TV_MINI_PLAYER_TAG = "tv-mini-player"

@Stable
internal class TvMiniPlayerState {
    var paused by mutableStateOf(true)
        private set
    private var setPlaying: ((Boolean) -> Unit)? = null

    fun togglePlaying(): Boolean {
        val control = setPlaying ?: return false
        control(paused)
        return true
    }

    internal fun show(paused: Boolean) {
        this.paused = paused
    }

    internal fun bind(control: (Boolean) -> Unit) {
        setPlaying = control
    }

    internal fun unbind(control: (Boolean) -> Unit) {
        if (setPlaying !== control) return
        setPlaying = null
        paused = true
    }
}

@Composable
internal fun TvMiniPlayer(
    title: String,
    state: TvMiniPlayerState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = TvButtonSize.Header
    Row(
        modifier = modifier.testTag(TV_MINI_PLAYER_TAG),
        horizontalArrangement = Arrangement.spacedBy(MINI_PLAYER_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvButton(onClick = { if (!state.togglePlaying()) onOpen() }, size = size) {
            Icon(
                painter = painterResource(
                    if (state.paused) DesignR.drawable.ic_ph_play_fill else DesignR.drawable.ic_ph_pause_fill,
                ),
                contentDescription = stringResource(if (state.paused) R.string.tv_player_play else R.string.tv_player_pause),
                modifier = Modifier.size(size.icon),
            )
        }
        TvButton(onClick = onOpen, size = size, hugsLabel = true) {
            Icon(
                painter = painterResource(fileTypeIconRes(PutioFileType.AUDIO)),
                contentDescription = stringResource(R.string.tv_mini_player_open),
                modifier = Modifier.size(size.icon),
            )
            Text(
                text = title,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.widthIn(max = MINI_PLAYER_TITLE_WIDTH),
            )
        }
    }
}

private val MINI_PLAYER_GAP = 6.dp
private val MINI_PLAYER_TITLE_WIDTH = 240.dp
