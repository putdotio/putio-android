package io.putdotio.android.account

import io.putdotio.android.PutioResult
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.grants.OAuthClient
import io.putdotio.sdk.grants.OAuthGrant
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkLoggedInAppsRepositoryTest {
    @Test
    fun eachGrantShowsItsLastUseThisDeviceAndOnlyAnIconPutioHas() = runBlocking {
        val repository = repository(
            grants = listOf(
                OAuthGrant(id = 5L, name = "Infuse", hasIcon = false),
                OAuthGrant(id = 2L, name = "put.io for iOS", hasIcon = true),
                OAuthGrant(id = 9677L, name = "put.io for Android", hasIcon = true),
                OAuthGrant(id = 7L, name = "Old script"),
            ),
            clients = listOf(
                OAuthClient(id = 1L, appId = 2L, lastUsedAt = "2026-11-01T08:00:00"),
                OAuthClient(id = 2L, appId = 2L, lastUsedAt = "2026-11-09T12:30:00"),
                OAuthClient(id = 3L, appId = 9677L, lastUsedAt = "2026-11-12T09:00:00", active = true),
                OAuthClient(id = 4L, appId = 9677L, lastUsedAt = "2026-11-12T10:00:00"),
                OAuthClient(id = 5L, appId = 5L, lastUsedAt = "2026-10-30T20:00:00"),
            ),
        )

        val apps = (repository.list() as PutioResult.Success).value

        assertEquals(
            listOf(
                LoggedInApp(LoggedInAppId(9677L), "put.io for Android", at("2026-11-12T10:00:00Z"), true, icon(9677L)),
                LoggedInApp(LoggedInAppId(2L), "put.io for iOS", at("2026-11-09T12:30:00Z"), false, icon(2L)),
                LoggedInApp(LoggedInAppId(5L), "Infuse", at("2026-10-30T20:00:00Z"), false, null),
                LoggedInApp(LoggedInAppId(7L), "Old script", null, false, null),
            ),
            apps,
        )
    }

    @Test
    fun revokeEndsEverySessionOfThatApp() = runBlocking {
        val revoked = mutableListOf<Long>()
        val repository = repository(onRevoke = { revoked += it })

        repository.revoke(LoggedInAppId(2L))

        assertEquals(listOf(2L), revoked)
    }

    private fun repository(
        grants: List<OAuthGrant> = emptyList(),
        clients: List<OAuthClient> = emptyList(),
        onRevoke: (Long) -> Unit = {},
    ) = SdkLoggedInAppsRepository(
        listGrants = { grants },
        listClients = { clients },
        iconUrl = ::icon,
        revokeGrant = { onRevoke(it); OkResponse("OK") },
    )

    private fun icon(appId: Long): PutioCredentialUrl =
        PutioCredentialUrl.of("https://api.put.io/v2/oauth/apps/$appId/icon?oauth_token=t")

    private fun at(value: String): Instant = Instant.parse(value)
}
