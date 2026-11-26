package io.putdotio.android.files

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

public data class FilesTextPreview(
    val lines: List<String>,
    val truncated: Boolean,
)

public fun InputStream.readTextPreview(
    limitBytes: Int = TEXT_PREVIEW_LIMIT_BYTES,
    keepReading: () -> Boolean = { true },
): FilesTextPreview {
    val bytes = ByteArray(limitBytes)
    var count = 0
    while (count < limitBytes) {
        if (!keepReading()) throw CancellationException("The viewer closed")
        val read = read(bytes, count, limitBytes - count)
        if (read < 0) break
        count += read
    }
    val truncated = count == limitBytes && read() >= 0
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val input = ByteBuffer.wrap(bytes, 0, count)
    val output = CharBuffer.allocate(count)
    decoder.decode(input, output, !truncated)
    if (!truncated) decoder.flush(output)
    output.flip()
    val text = output.toString().removePrefix(Char(BYTE_ORDER_MARK).toString())
    return FilesTextPreview(text.split(LINE_BREAK), truncated)
}

public const val TEXT_PREVIEW_LIMIT_BYTES: Int = 250_000

private const val BYTE_ORDER_MARK = 0xFEFF
private val LINE_BREAK = Regex("\r\n|\r|\n")
