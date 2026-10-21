package io.putdotio.android.design

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.putdotio.sdk.files.PutioFileType

/**
 * Yellow Phosphor file icons — the icon family is a cross-tier decision
 * (putio-design platforms/android/DESIGN.md). Folders take the fill weight,
 * file kinds the regular weight. SWF has no Phosphor glyph of its own and
 * rides file-code; unknown kinds fall back to the plain file glyph.
 */
@DrawableRes
public fun fileTypeIconRes(type: PutioFileType): Int = when (type) {
    PutioFileType.FOLDER -> R.drawable.ic_ph_folder_fill
    PutioFileType.AUDIO -> R.drawable.ic_ph_file_audio
    PutioFileType.VIDEO -> R.drawable.ic_ph_file_video
    PutioFileType.IMAGE -> R.drawable.ic_ph_file_image
    PutioFileType.ARCHIVE -> R.drawable.ic_ph_file_zip
    PutioFileType.PDF -> R.drawable.ic_ph_file_pdf
    PutioFileType.TEXT -> R.drawable.ic_ph_file_text
    PutioFileType.SWF -> R.drawable.ic_ph_file_code
    else -> R.drawable.ic_ph_file
}

@Composable
public fun FileTypeIcon(
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
