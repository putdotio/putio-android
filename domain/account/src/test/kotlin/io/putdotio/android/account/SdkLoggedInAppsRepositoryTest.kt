package io.putdotio.android.account

import io.putdotio.android.PutioResult
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.files.PutioMediaRequest
import io.putdotio.sdk.grants.OAuthClient
import io.putdotio.sdk.grants.OAuthGrant
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
                LoggedInApp(
                    LoggedInAppId(9677L), "put.io for Android", at("2026-11-12T10:00:00Z"), true, icon(9677L),
                    otherSessions = listOf(LoggedInSessionId(4L)),
                ),
                LoggedInApp(LoggedInAppId(2L), "put.io for iOS", at("2026-11-09T12:30:00Z"), false, icon(2L)),
                LoggedInApp(LoggedInAppId(5L), "Infuse", at("2026-10-30T20:00:00Z"), false, null),
                LoggedInApp(LoggedInAppId(7L), "Old script", null, false, null),
            ),
            apps,
        )
    }

    @Test
    fun anIconLoadsFromATokenFreeUrlWithTheAccessTokenInItsHeader() = runBlocking {
        val repository = SdkLoggedInAppsRepository(sdkClient())

        val icon = (repository.list() as PutioResult.Success).value.single().icon

        assertEquals("https://api.put.io/v2/oauth/apps/2/icon", icon?.url)
        assertEquals(mapOf("Authorization" to "Token $ACCESS_TOKEN"), icon?.headers)
    }

    @Test
    fun revokeEndsEverySessionOfThatAppAndRevokeSessionEndsOne() = runBlocking {
        val revoked = mutableListOf<Long>()
        val revokedSessions = mutableListOf<Long>()
        val repository = repository(onRevoke = { revoked += it }, onRevokeSession = { revokedSessions += it })

        repository.revoke(LoggedInAppId(2L))
        repository.revokeSession(LoggedInSessionId(4L))

        assertEquals(listOf(2L), revoked)
        assertEquals(listOf(4L), revokedSessions)
    }

    private fun repository(
        grants: List<OAuthGrant> = emptyList(),
        clients: List<OAuthClient> = emptyList(),
        onRevoke: (Long) -> Unit = {},
        onRevokeSession: (Long) -> Unit = {},
    ) = SdkLoggedInAppsRepository(
        listGrants = { grants },
        listClients = { clients },
        iconRequest = ::icon,
        revokeGrant = { onRevoke(it); OkResponse("OK") },
        revokeClient = { onRevokeSession(it); OkResponse("OK") },
    )

    private fun icon(appId: Long): PutioMediaRequest =
        PutioClient(PutioConfig(accessToken = ACCESS_TOKEN)).grants.buildIconRequest(appId)

    private fun sdkClient(): PutioClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val body = if (chain.request().url.encodedPath.endsWith("/oauth/grants")) GRANTS else CLIENTS
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(HTTP_OK)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return PutioClient(PutioConfig(accessToken = ACCESS_TOKEN), http)
    }

    private fun at(value: String): Instant = Instant.parse(value)

    private companion object {
        const val HTTP_OK = 200
        const val ACCESS_TOKEN = "oauth-access-token"
        const val GRANTS = """{"status":"OK","apps":[{"id":2,"name":"put.io for iOS","has_icon":true}]}"""
        const val CLIENTS = """{"status":"OK","clients":[]}"""
    }
}
