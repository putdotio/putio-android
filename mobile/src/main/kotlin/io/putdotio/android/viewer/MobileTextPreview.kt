package io.putdotio.android.viewer

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

/** The start of a text file as the viewer shows it; [truncated] when the file goes on past it. */
internal data class MobileTextPreview(
    val lines: List<String>,
    val truncated: Boolean,
)

/**
 * Reads at most [limitBytes] of UTF-8 text, so a huge log never sits in memory whole. Malformed
 * bytes read as the replacement character; a character the limit cuts in half is dropped rather
 * than shown as one. A byte-order mark is skipped and line endings of every platform split lines.
 */
internal fun InputStream.readTextPreview(
    limitBytes: Int = TEXT_PREVIEW_LIMIT_BYTES,
    /** Checked between reads, so a closed viewer stops a slow stream at the next chunk. */
    keepReading: () -> Boolean = { true },
): MobileTextPreview {
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
    // endOfInput only when the whole file is here: a cut multi-byte character then stays undecoded.
    decoder.decode(input, output, !truncated)
    if (!truncated) decoder.flush(output)
    output.flip()
    val text = output.toString().removePrefix(Char(BYTE_ORDER_MARK).toString())
    return MobileTextPreview(text.split(LINE_BREAK), truncated)
}

/** A long subtitle file or log many times over, and still quick to lay out. */
internal const val TEXT_PREVIEW_LIMIT_BYTES = 250_000

private const val BYTE_ORDER_MARK = 0xFEFF
private val LINE_BREAK = Regex("\r\n|\r|\n")
