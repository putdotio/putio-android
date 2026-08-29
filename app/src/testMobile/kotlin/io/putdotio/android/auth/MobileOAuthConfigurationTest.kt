package io.putdotio.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileOAuthConfigurationTest {
    @Test
    fun `dedicated numeric client id configures exact redirect`() {
        val configuration = MobileOAuthConfiguration.fromClientId("9001")

        assertTrue(configuration is MobileOAuthConfiguration.Configured)
        configuration as MobileOAuthConfiguration.Configured
        assertEquals("9001", configuration.clientId)
        assertEquals("putio://auth", configuration.redirectUri)
    }

    @Test
    fun `missing malformed and tv ids fail closed`() {
        val unavailable = listOf(
            null,
            "",
            "0",
            " 9001",
            "+9001",
            "09001",
            "mobile",
            "6221",
            "06221",
            "+6221",
            "6233",
            "006233",
        )

        unavailable.forEach { clientId ->
            assertTrue(MobileOAuthConfiguration.fromClientId(clientId) is MobileOAuthConfiguration.Unavailable)
        }
    }
}
