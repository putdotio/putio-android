package io.putdotio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveProofFixtureTest {
    @Test
    fun acceptsSevenOwnedItemsAndRejectsAmbiguousFixtures() {
        val valid = """{"expectedAccountId":1,"containerId":11,"containerName":"move-proof-unique",
            "sourceId":12,"sourceName":"Source","destinationId":13,"destinationName":"Destination",
            "folderItemId":14,"folderName":"A Folder été","fileItemId":15,"fileName":"B File 東京.txt",
            "fileSize":40,"collisionItemId":16,"collisionPeerId":17,"collisionName":"Z Collision"}"""
        fun parse(value: String) = MoveProofFixture.parse(Base64.getEncoder().encodeToString(value.toByteArray()))
        assertEquals(14L, parse(valid).folderItemId)
        assertEquals("B File 東京.txt", parse(valid).fileName)
        for (invalid in listOf(
            valid.replace("\"folderItemId\":14", "\"folderItemId\":0"),
            valid.replace("\"folderItemId\":14", "\"folderItemId\":-1"),
            valid.replace("\"folderItemId\":14", "\"folderItemId\":\"14\""),
            valid.replace("\"folderItemId\":14", "\"folderItemId\":14.0"),
            valid.replace("\"collisionPeerId\":17", "\"collisionPeerId\":16"),
            valid.replace("\"fileSize\":40", "\"fileSize\":129"),
            valid.replace("\"sourceName\":\"Source\"", "\"sourceName\":\"Destination\""),
            valid.replace("\"sourceName\":\"Source\"", "\"sourceName\":\"\""),
            valid.replace("\"sourceId\":12", "\"sourceId\":12,\"sourceId\":18"),
            valid.replace("\"sourceId\":12", "\"sourceId\":12,\"rootId\":0"),
            valid.replace("\"sourceId\":12,", ""),
            "$valid trailing",
        )) assertThrows(AssertionError::class.java) { parse(invalid) }
        assertThrows(AssertionError::class.java) { MoveProofFixture.parse(null) }
    }
}
