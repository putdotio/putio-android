package io.putdotio.android.account

import io.putdotio.android.PutioResult
import java.time.Instant

public class FakeLoggedInAppsRepository(
    apps: List<LoggedInApp> = emptyList(),
) : LoggedInAppsRepository {
    public val apps: MutableList<LoggedInApp> = apps.toMutableList()
    public val revoked: MutableList<LoggedInAppId> = mutableListOf()
    public var listCount: Int = 0
    public var onList: suspend () -> PutioResult<List<LoggedInApp>> = { PutioResult.Success(this.apps.toList()) }
    public var onRevoke: suspend (LoggedInAppId) -> PutioResult<Unit> = { id ->
        this.apps.removeAll { it.id == id }
        PutioResult.Success(Unit)
    }

    override suspend fun list(): PutioResult<List<LoggedInApp>> {
        listCount += 1
        return onList()
    }

    override suspend fun revoke(id: LoggedInAppId): PutioResult<Unit> {
        revoked += id
        return onRevoke(id)
    }
}

public fun loggedInApp(
    id: Long,
    name: String = "App $id",
    lastUsedAt: Instant? = null,
    thisDevice: Boolean = false,
): LoggedInApp = LoggedInApp(LoggedInAppId(id), name, lastUsedAt, thisDevice, icon = null)
