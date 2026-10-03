package io.putdotio.android.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class TvOAuthClientTest {
    @Test
    fun `Fire TV links as its own OAuth app and everything else as Android TV`() {
        assertEquals("6233", TvOAuthClient.select(isFireTv = true).clientId)
        assertEquals("6221", TvOAuthClient.select(isFireTv = false).clientId)
        assertEquals("put.io Fire TV", TvOAuthClient.select(isFireTv = true).clientName)
        assertEquals("put.io Android TV", TvOAuthClient.select(isFireTv = false).clientName)
    }
}
