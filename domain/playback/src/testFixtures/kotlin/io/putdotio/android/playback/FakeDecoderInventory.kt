package io.putdotio.android.playback

import androidx.media3.common.Format

public class FakeDecoderInventory(
    private val mimeTypes: Set<String>,
    private val rejects: (Format) -> Boolean = { false },
) : DecoderInventory {
    public val asked: MutableList<Format> = mutableListOf()

    override fun plays(format: Format): Boolean {
        asked += format
        return format.sampleMimeType in mimeTypes && !rejects(format)
    }
}
