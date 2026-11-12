package io.putdotio.android.account

import io.putdotio.android.PutioResult
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.putioRequest
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.grants.OAuthClient
import io.putdotio.sdk.grants.OAuthGrant
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@JvmInline
public value class LoggedInAppId(
    public val value: Long,
)

public data class LoggedInApp(
    val id: LoggedInAppId,
    val name: String,
    val lastUsedAt: Instant?,
    val thisDevice: Boolean,
    val icon: PutioCredentialUrl?,
)

public interface LoggedInAppsRepository {
    public suspend fun list(): PutioResult<List<LoggedInApp>>

    public suspend fun revoke(id: LoggedInAppId): PutioResult<Unit>
}

public class SdkLoggedInAppsRepository internal constructor(
    private val listGrants: suspend () -> List<OAuthGrant>,
    private val listClients: suspend () -> List<OAuthClient>,
    private val iconUrl: (Long) -> PutioCredentialUrl?,
    private val revokeGrant: suspend (Long) -> OkResponse,
) : LoggedInAppsRepository {
    public constructor(client: PutioClient) : this(
        listGrants = { client.grants.list() },
        listClients = { client.grants.clients() },
        iconUrl = { appId -> client.config.accessToken?.let { client.grants.buildIconUrl(appId, it) } },
        revokeGrant = { client.grants.revoke(it) },
    )

    override suspend fun list(): PutioResult<List<LoggedInApp>> = putioRequest {
        coroutineScope {
            val grants = async { listGrants() }
            val clients = async { listClients() }
            loggedInApps(grants.await(), clients.await(), iconUrl)
        }
    }

    override suspend fun revoke(id: LoggedInAppId): PutioResult<Unit> = putioRequest {
        require(id.value > 0L) { "Revoking needs a positive app ID" }
        revokeGrant(id.value)
        Unit
    }
}

internal fun loggedInApps(
    grants: List<OAuthGrant>,
    clients: List<OAuthClient>,
    iconUrl: (Long) -> PutioCredentialUrl?,
): List<LoggedInApp> {
    val sessions = clients.groupBy(OAuthClient::appId)
    return grants
        .filter { it.id > 0L }
        .distinctBy(OAuthGrant::id)
        .map { grant ->
            val appSessions = sessions[grant.id].orEmpty()
            LoggedInApp(
                id = LoggedInAppId(grant.id),
                name = grant.name.ifBlank { appSessions.firstNotNullOfOrNull(OAuthClient::appName).orEmpty() },
                lastUsedAt = appSessions.mapNotNull { session -> session.lastUsedAt?.let(::parsePutioTimestamp) }
                    .maxOrNull(),
                thisDevice = appSessions.any(OAuthClient::active),
                icon = if (grant.hasIcon == true) iconUrl(grant.id) else null,
            )
        }
        .sortedWith(
            compareByDescending<LoggedInApp> { it.thisDevice }
                .thenByDescending { it.lastUsedAt ?: Instant.MIN }
                .thenBy { it.name.lowercase() },
        )
}
