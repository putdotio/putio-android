package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFolderUpdatedTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()

    @Test
    fun aFolderReadsItsLastChangeAsTimeAgo() {
        assertEquals("3 days ago", item(PutioFileType.FOLDER, updatedAt = "2026-10-28T12:00:00").updatedAgo(now))
        assertEquals("24 min. ago", item(PutioFileType.FOLDER, updatedAt = "2026-10-31T11:36:00").updatedAgo(now))
    }

    @Test
    fun aChangeOverAWeekAgoReadsAsAFileDateDoes() {
        assertEquals("Oct 7", item(PutioFileType.FOLDER, updatedAt = "2026-10-07T12:00:00").updatedAgo(now))
        assertEquals("Aug 3, 2024", item(PutioFileType.FOLDER, updatedAt = "2024-08-03T12:00:00").updatedAgo(now))
    }

    @Test
    fun aChangeAheadOfTheClockReadsAsNow() {
        assertEquals("0 min. ago", item(PutioFileType.FOLDER, updatedAt = "2026-10-31T12:05:00").updatedAgo(now))
    }

    @Test
    fun aFileOrAFolderWithoutATimeHasNone() {
        assertNull(item(PutioFileType.VIDEO, updatedAt = "2026-10-28T12:00:00").updatedAgo(now))
        assertNull(item(PutioFileType.FOLDER, updatedAt = null).updatedAgo(now))
    }

    private fun item(type: PutioFileType, updatedAt: String?) = FilesItem(
        id = FilesItemId(1),
        parentId = FilesItemId(0),
        name = "backups",
        type = type,
        sizeBytes = 0,
        createdAt = "2020-05-05T12:00:00",
        updatedAt = updatedAt,
    )
}
