package io.putdotio.android.tv.files

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.design.R as DesignR

@Composable
internal fun TvFilesAction.label(): String =
    stringResource(
        when (this) {
            TvFilesAction.Resume -> R.string.tv_files_action_resume
            TvFilesAction.Play -> R.string.tv_files_detail_play
            TvFilesAction.StartOver -> R.string.tv_files_detail_start_over
            is TvFilesAction.PlayWith -> method.playWithLabel()
            TvFilesAction.OpenInVlc -> R.string.tv_files_action_vlc
            is TvFilesAction.SetWatched -> labelRes()
            is TvFilesAction.SetPinned -> labelRes()
            is TvFilesAction.Delete -> labelRes()
            TvFilesAction.Rename -> R.string.tv_files_action_rename
            TvFilesAction.Move -> R.string.tv_files_action_move
            TvFilesAction.GoToFolder -> R.string.tv_history_go_to_folder
            TvFilesAction.RemoveFromContinueWatching -> R.string.tv_home_remove_continue_watching
        },
    )

@StringRes
private fun TvFilesAction.SetWatched.labelRes(): Int =
    if (watched) R.string.tv_files_action_mark_watched else R.string.tv_files_action_mark_unwatched

@StringRes
private fun TvFilesAction.SetPinned.labelRes(): Int =
    if (pinned) R.string.tv_files_action_pin else R.string.tv_files_action_unpin

@StringRes
private fun TvFilesAction.Delete.labelRes(): Int =
    if (trash) R.string.tv_files_action_trash else R.string.tv_files_action_delete

@StringRes
private fun StreamingMethod.playWithLabel(): Int =
    when (this) {
        StreamingMethod.Hls -> R.string.tv_files_action_play_standard
        StreamingMethod.File -> R.string.tv_files_action_play_direct
    }

@DrawableRes
internal fun TvFilesAction.icon(): Int =
    when (this) {
        TvFilesAction.Resume, TvFilesAction.Play -> DesignR.drawable.ic_ph_play_fill
        TvFilesAction.StartOver -> R.drawable.ic_ph_arrow_counter_clockwise
        is TvFilesAction.PlayWith -> R.drawable.ic_ph_play_circle
        TvFilesAction.OpenInVlc -> R.drawable.ic_ph_traffic_cone
        is TvFilesAction.SetWatched -> if (watched) R.drawable.ic_ph_eye else R.drawable.ic_ph_eye_slash
        is TvFilesAction.SetPinned -> if (pinned) R.drawable.ic_ph_push_pin else R.drawable.ic_ph_push_pin_slash
        TvFilesAction.Rename -> R.drawable.ic_ph_pencil_simple
        TvFilesAction.Move -> R.drawable.ic_ph_folder_simple_dashed
        TvFilesAction.GoToFolder -> R.drawable.ic_ph_folder_open
        is TvFilesAction.Delete -> R.drawable.ic_ph_trash
        TvFilesAction.RemoveFromContinueWatching -> R.drawable.ic_ph_x
    }
