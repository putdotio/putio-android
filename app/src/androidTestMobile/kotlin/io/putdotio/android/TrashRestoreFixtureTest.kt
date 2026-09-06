package io.putdotio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashRestoreFixtureTest {
    @Test
    fun acceptsOnlyTheOwnedFourItemFixture() {
        val parsed = parse(valid)
        assertEquals(runId, parsed.runId)
        assertEquals(1L, parsed.expectedAccountId)
        assertEquals(11L, parsed.container.id)
        assertEquals(0L, parsed.container.parentId)
        assertEquals("FILE", parsed.file.fileType)
        assertEquals(12L, parsed.file.id)
        assertEquals(11L, parsed.file.parentId)
        assertEquals("été-$runId.txt", parsed.file.name)
        assertEquals(40L, parsed.fileSize)
        assertEquals(13L, parsed.cancel.id)
        assertEquals(14L, parsed.sentinel.id)
        assertEquals("TEXT", parse(valid.replace("\"fileFileType\":\"FILE\"", "\"fileFileType\":\"TEXT\"")).file.fileType)
        assertEquals(1L, parse(valid.replace("\"fileSize\":40", "\"fileSize\":1")).fileSize)
        assertEquals(128L, parse(valid.replace("\"fileSize\":40", "\"fileSize\":128")).fileSize)
        val invalid = listOf(
            valid.replace("\"fileId\":12", "\"fileId\":0"),
            valid.replace("\"fileId\":12", "\"fileId\":-1"),
            valid.replace("\"fileId\":12", "\"fileId\":\"12\""),
            valid.replace("\"fileId\":12", "\"fileId\":12.0"),
            valid.replace("\"fileId\":12", "\"fileId\":1.2e1"),
            valid.replace("\"fileId\":12", "\"fileId\":9223372036854775808"),
            valid.replace("\"fileId\":12", "\"fileId\":null"),
            valid.replace("\"sentinelId\":14", "\"sentinelId\":13"),
            valid.replace("\"expectedAccountId\":1", "\"expectedAccountId\":0"),
            valid.replace("\"fileSize\":40", "\"fileSize\":0"),
            valid.replace("\"fileSize\":40", "\"fileSize\":129"),
            valid.replace("\"containerParentId\":0", "\"containerParentId\":7"),
            valid.replace("\"fileParentId\":11", "\"fileParentId\":0"),
            valid.replace("\"cancelParentId\":11", "\"cancelParentId\":12"),
            valid.replace("\"fileFileType\":\"FILE\"", "\"fileFileType\":\"FOLDER\""),
            valid.replace("\"cancelFileType\":\"FOLDER\"", "\"cancelFileType\":\"FILE\""),
            valid.replace("sentinelle-東京-", "annuler-東京-"),
            valid.replace("été-", "file-"),
            valid.replace("été-$runId.txt", "été.txt"),
            valid.replace("été-$runId.txt", ""),
            valid.replace("été-", "\\nété-"),
            valid.replace("été-", "\\uD800été-"),
            valid.replace("android-trash-restore-proof-", "other-proof-"),
            valid.replace("\"fileId\":12", "\"fileId\":12,\"fileId\":15"),
            valid.replace("\"fileId\":12", "\"fileId\":12,\"cursor\":\"secret-payload\""),
            valid.replace("\"fileId\":12,", ""),
            valid.replace("\"runId\":\"$runId\"", "\"runId\":\"00000000-0000-4000-8000-000000000002\""),
            "$valid trailing", "$valid{}", "[]",
        )
        invalid.forEachIndexed { index, input ->
            val error = assertThrows("Invalid fixture case $index", AssertionError::class.java) { parse(input) }
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("secret-payload"))
        }
        for (encoded in listOf(null, "not base64", "A".repeat(32_769),
            Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28)))) {
            assertThrows(AssertionError::class.java) { TrashRestoreFixture.parse(encoded, runId) }
        }
        for (argument in listOf(null, "1-1-1-1-1", "not-a-uuid", "00000000-0000-4000-8000-000000000002")) {
            assertThrows(AssertionError::class.java) { TrashRestoreFixture.parse(encode(valid), argument) }
        }
    }

    private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private fun parse(value: String) = TrashRestoreFixture.parse(encode(value), runId)
    private val runId = "00000000-0000-4000-8000-000000000001"
    private val valid = """{"runId":"$runId","expectedAccountId":1,
        "containerId":11,"containerName":"android-trash-restore-proof-$runId",
        "containerParentId":0,"containerFileType":"FOLDER",
        "fileId":12,"fileName":"été-$runId.txt","fileParentId":11,"fileFileType":"FILE","fileSize":40,
        "cancelId":13,"cancelName":"annuler-東京-$runId","cancelParentId":11,"cancelFileType":"FOLDER",
        "sentinelId":14,"sentinelName":"sentinelle-東京-$runId","sentinelParentId":11,"sentinelFileType":"FOLDER"}"""
}
