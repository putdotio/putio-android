package io.putdotio.android.design

import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypeIconTest {

    @Test
    fun everyKnownKindHasItsOwnGlyph() {
        val known = listOf(
            PutioFileType.FOLDER,
            PutioFileType.FILE,
            PutioFileType.AUDIO,
            PutioFileType.VIDEO,
            PutioFileType.IMAGE,
            PutioFileType.ARCHIVE,
            PutioFileType.PDF,
            PutioFileType.TEXT,
            PutioFileType.SWF,
        )
        val icons = known.map(::fileTypeIconRes)
        assertEquals("each kind maps to a distinct drawable", known.size, icons.toSet().size)
    }

    @Test
    fun unknownKindsFallBackToThePlainFileGlyph() {
        assertEquals(
            fileTypeIconRes(PutioFileType.FILE),
            fileTypeIconRes(PutioFileType.fromRaw("SOMETHING_NEW")),
        )
    }
}
