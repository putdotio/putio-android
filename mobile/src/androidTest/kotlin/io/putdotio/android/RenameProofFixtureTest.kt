package io.putdotio.android

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RenameProofFixtureTest {
    @Test
    fun enforcesUnicodeAndSpacesForDirectInstrumentationFixtures() {
        val expected = fixture()
        val parsed = RenameProofFixture.parse(encode(expected))
        assertEquals(expected.getString("renameOriginalName"), parsed.renameOriginalName)
        assertEquals(expected.getString("renameNewName"), parsed.renameNewName)
        for (key in listOf("renameOriginalName", "renameNewName")) {
            for (name in listOf("ASCII name", "東京", "été\tfile")) {
                val encoded = encode(fixture().put(key, name))
                assertThrows(AssertionError::class.java) { RenameProofFixture.parse(encoded) }
            }
        }
    }

    private fun fixture() = JSONObject()
        .put("expectedAccountId", 11)
        .put("containerId", 12)
        .put("renameItemId", 13)
        .put("cancelItemId", 14)
        .put("containerName", "Owned container")
        .put("renameOriginalName", "  été e\u0301  ")
        .put("renameNewName", "  東京 é  ")
        .put("cancelOriginalName", "Cancel me")

    private fun encode(fixture: JSONObject): String =
        Base64.getEncoder().encodeToString(fixture.toString().toByteArray(Charsets.UTF_8))
}
