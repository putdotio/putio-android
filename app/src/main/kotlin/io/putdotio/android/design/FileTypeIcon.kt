package io.putdotio.android.design

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.putdotio.android.R
import io.putdotio.sdk.files.PutioFileType

/**
 * Yellow Phosphor file icons, every kind — the icon family is a cross-tier
 * decision (putio-design platforms/android/DESIGN.md). SWF has no Phosphor
 * glyph of its own and rides file-code; unknown kinds fall back to the plain
 * file glyph.
 */
@DrawableRes
fun fileTypeIconRes(type: PutioFileType): Int = when (type) {
    PutioFileType.FOLDER -> R.drawable.ic_ph_folder_fill
    PutioFileType.AUDIO -> R.drawable.ic_ph_file_audio_fill
    PutioFileType.VIDEO -> R.drawable.ic_ph_file_video_fill
    PutioFileType.IMAGE -> R.drawable.ic_ph_file_image_fill
    PutioFileType.ARCHIVE -> R.drawable.ic_ph_file_zip_fill
    PutioFileType.PDF -> R.drawable.ic_ph_file_pdf_fill
    PutioFileType.TEXT -> R.drawable.ic_ph_file_text_fill
    PutioFileType.SWF -> R.drawable.ic_ph_file_code_fill
    else -> R.drawable.ic_ph_file_fill
}

@Composable
fun FileTypeIcon(
    type: PutioFileType,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(fileTypeIconRes(type)),
        contentDescription = contentDescription,
        tint = PutioDesignTokens.yellowSolid,
        modifier = modifier,
    )
}
