package io.putdotio.android.account

import io.putdotio.android.PutioResult
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.putioRequest
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.PutioMediaRequest
import io.putdotio.sdk.grants.OAuthClient
import io.putdotio.sdk.grants.OAuthGrant
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@JvmInline
public value class LoggedInAppId(
    public val value: Long,
)

@JvmInline
public value class LoggedInSessionId(
    public val value: Long,
)

public data class LoggedInApp(
    val id: LoggedInAppId,
    val name: String,
    val lastUsedAt: Instant?,
    val thisDevice: Boolean,
    val icon: PutioMediaRequest?,
    val otherSessions: List<LoggedInSessionId> = emptyList(),
)

public interface LoggedInAppsRepository {
    public suspend fun list(): PutioResult<List<LoggedInApp>>

    public suspend fun revoke(id: LoggedInAppId): PutioResult<Unit>

    public suspend fun revokeSession(id: LoggedInSessionId): PutioResult<Unit>
}

public class SdkLoggedInAppsRepository internal constructor(
    private val listGrants: suspend () -> List<OAuthGrant>,
    private val listClients: suspend () -> List<OAuthClient>,
    private val iconRequest: (Long) -> PutioMediaRequest?,
    private val revokeGrant: suspend (Long) -> OkResponse,
    private val revokeClient: suspend (Long) -> OkResponse,
) : LoggedInAppsRepository {
    public constructor(client: PutioClient) : this(
        listGrants = { client.grants.list() },
        listClients = { client.grants.clients() },
        iconRequest = { appId -> client.config.accessToken?.let { client.grants.buildIconRequest(appId) } },
        revokeGrant = { client.grants.revoke(it) },
        revokeClient = { client.grants.revokeClient(it) },
    )

    override suspend fun list(): PutioResult<List<LoggedInApp>> = putioRequest {
        coroutineScope {
            val grants = async { listGrants() }
            val clients = async { listClients() }
            loggedInApps(grants.await(), clients.await(), iconRequest)
        }
    }

    override suspend fun revoke(id: LoggedInAppId): PutioResult<Unit> = putioRequest {
        require(id.value > 0L) { "Revoking needs a positive app ID" }
        revokeGrant(id.value)
        Unit
    }

    override suspend fun revokeSession(id: LoggedInSessionId): PutioResult<Unit> = putioRequest {
        require(id.value > 0L) { "Revoking needs a positive session ID" }
        revokeClient(id.value)
        Unit
    }
}

internal fun loggedInApps(
    grants: List<OAuthGrant>,
    clients: List<OAuthClient>,
    iconRequest: (Long) -> PutioMediaRequest?,
): List<LoggedInApp> {
    val sessions = clients.groupBy(OAuthClient::appId)
    return grants
        .filter { it.id > 0L }
        .distinctBy(OAuthGrant::id)
        .map { grant ->
            val appSessions = sessions[grant.id].orEmpty()
            val thisDevice = appSessions.any(OAuthClient::active)
            LoggedInApp(
                id = LoggedInAppId(grant.id),
                name = grant.name.ifBlank { appSessions.firstNotNullOfOrNull(OAuthClient::appName).orEmpty() },
                lastUsedAt = appSessions.mapNotNull { session -> session.lastUsedAt?.let(::parsePutioTimestamp) }
                    .maxOrNull(),
                thisDevice = thisDevice,
                icon = if (grant.hasIcon == true) iconRequest(grant.id) else null,
                otherSessions = if (thisDevice) {
                    appSessions.filterNot(OAuthClient::active).map { LoggedInSessionId(it.id) }
                } else {
                    emptyList()
                },
            )
        }
        .sortedWith(
            compareByDescending<LoggedInApp> { it.thisDevice }
                .thenByDescending { it.lastUsedAt ?: Instant.MIN }
                .thenBy { it.name.lowercase() },
        )
}
