package io.putdotio.android.auth

import io.putdotio.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileOAuthConfigurationTest {
    @Test
    fun `registered android mobile client id is wired`() {
        val configuration = MobileOAuthConfiguration.fromClientId(BuildConfig.PUTIO_MOBILE_OAUTH_CLIENT_ID)

        assertEquals("9677", BuildConfig.PUTIO_MOBILE_OAUTH_CLIENT_ID)
        assertTrue(configuration is MobileOAuthConfiguration.Configured)
        configuration as MobileOAuthConfiguration.Configured
        assertEquals("putio://auth", configuration.redirectUri)
    }

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

class MobileOAuthDebugOverrideTest {
    @Test
    fun `empty override keeps the registered client and tv guard`() {
        assertEquals(
            MobileOAuthConfiguration.Configured("9677"),
            MobileOAuthConfiguration.fromClientId("9677", debugOverride = ""),
        )
        assertTrue(MobileOAuthConfiguration.fromClientId("6221", debugOverride = "") is MobileOAuthConfiguration.Unavailable)
    }

    @Test
    fun `override replaces the client and may borrow a tv client`() {
        assertEquals(
            MobileOAuthConfiguration.Configured("6221"),
            MobileOAuthConfiguration.fromClientId("9677", debugOverride = "6221"),
        )
    }

    @Test
    fun `malformed override still fails closed`() {
        listOf("0", "-1", " 6221", "06221", "tv").forEach { override ->
            assertTrue(
                MobileOAuthConfiguration.fromClientId("9677", debugOverride = override) is MobileOAuthConfiguration.Unavailable,
            )
        }
    }
}
