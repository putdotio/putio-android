package io.putdotio.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileDestinationTest {

    @Test
    fun destinationsMatchTheMobileContract() {
        assertEquals(
            listOf("files", "search", "transfers", "account"),
            MobileDestination.entries.map(MobileDestination::route),
        )
    }
}
