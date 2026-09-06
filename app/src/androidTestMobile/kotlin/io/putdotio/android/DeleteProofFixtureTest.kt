package io.putdotio.android

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeleteProofFixtureTest {
    @Test
    fun acceptsBothExplicitModesAndRejectsAmbiguousMutationFixtures() {
        for (trash in listOf(true, false)) {
            val parsed = DeleteProofFixture.parse(encode(fixture().put("expectedTrashEnabled", trash).toString()))
            assertEquals(trash, parsed.expectedTrashEnabled)
            assertEquals("A été 東京", parsed.actionName)
        }
        val invalid = listOf(
            fixture().put("actionItemId", 0).toString(),
            fixture().put("actionItemId", 14).toString(),
            fixture().put("actionItemId", "13").toString(),
            fixture().put("expectedTrashEnabled", "true").toString(),
            fixture().put("extra", true).toString(),
            fixture().put("actionName", "").toString(),
            fixture().toString().dropLast(1) + ",\"actionItemId\":15}",
        )
        for (raw in invalid) assertThrows(AssertionError::class.java) { DeleteProofFixture.parse(encode(raw)) }
    }

    private fun fixture() = JSONObject()
        .put("expectedAccountId", 11)
        .put("containerId", 12)
        .put("actionItemId", 13)
        .put("cancelItemId", 14)
        .put("containerName", "Owned container")
        .put("actionName", "A été 東京")
        .put("cancelName", "Z Keep me")
        .put("expectedTrashEnabled", true)

    private fun encode(raw: String): String = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}
