package io.putdotio.android.tv

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.floor

/**
 * Encodes [text] at error correction M, which a phone camera reads from across a room: the
 * code's square of modules by row, true for dark, without the quiet zone.
 */
internal fun encodeTvQr(text: String): Array<BooleanArray> {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
}

/**
 * [text] as a QR code: [dark] modules on a [light] square that includes the four-module quiet
 * zone scanners need. Modules snap to whole pixels so no seam blurs them.
 */
@Composable
internal fun TvQrCode(
    text: String,
    description: String,
    dark: Color,
    light: Color,
    modifier: Modifier = Modifier,
) {
    val modules = remember(text) { encodeTvQr(text) }
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRect(light)
        val span = modules.size + 2 * QR_QUIET_ZONE_MODULES
        val cell = floor(size.minDimension / span).coerceAtLeast(1f)
        val left = floor((size.width - cell * modules.size) / 2)
        val top = floor((size.height - cell * modules.size) / 2)
        for (y in modules.indices) {
            for (x in modules.indices) {
                if (modules[y][x]) {
                    drawRect(dark, topLeft = Offset(left + x * cell, top + y * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

internal const val QR_QUIET_ZONE_MODULES = 4
