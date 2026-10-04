package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.apiReason
import io.putdotio.android.findPutioApiException
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.account.AccountSettingsPatch
import io.putdotio.sdk.errors.PutioException
import io.putdotio.android.files.FilesSort
import io.putdotio.sdk.routes.TunnelRoute
import java.util.concurrent.CancellationException

public sealed interface AccountSettingsRepositoryResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : AccountSettingsRepositoryResult<T>

    public data class Failure(
        val failure: AccountSettingsFailure,
    ) : AccountSettingsRepositoryResult<Nothing>
}

public sealed interface AccountSettingsFailure {
    public val cause: Throwable

    /** A put.io failure, classified by [toSettingsFailure]. */
    public data class Putio(
        val failure: PutioFailure,
    ) : AccountSettingsFailure {
        override val cause: Throwable
            get() = failure.cause
    }

    /** The server refused the chosen proxy for this account (403 `UNAVAILABLE_VALUE`). */
    public data class RouteUnavailable(
        override val cause: PutioException,
    ) : AccountSettingsFailure
}

/** The put.io failure behind this one; null for a refusal only account settings explains. */
public val AccountSettingsFailure.putioFailure: PutioFailure?
    get() = (this as? AccountSettingsFailure.Putio)?.failure

/** put.io's own reason for a refused request; the surface's copy applies when it is null. */
public val AccountSettingsFailure.apiReason: String?
    get() = putioFailure?.apiReason

public interface AccountSettingsRepository {
    public suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences>

    public suspend fun save(change: AccountSettingsChange): AccountSettingsRepositoryResult<Unit>

    /** Selectable tunnel routes for this account; `default` is always first. */
    public suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>>
}

public class SdkAccountSettingsRepository(
    private val getSettings: suspend () -> AccountSettings,
    private val saveSettings: suspend (AccountSettingsPatch) -> Unit,
    private val listRoutes: suspend () -> List<TunnelRoute> = { error("Tunnel routes are unavailable") },
) : AccountSettingsRepository {
    public constructor(client: PutioClient) : this(
        getSettings = client.account::getSettings,
        saveSettings = { patch -> client.account.saveSettings(patch) },
        listRoutes = client.routes::list,
    )

    override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> =
        request { getSettings().toPreferences() }

    override suspend fun save(
        change: AccountSettingsChange,
    ): AccountSettingsRepositoryResult<Unit> =
        request(routeChange = change is AccountSettingsChange.Route) {
            saveSettings(change.toPatch())
        }

    override suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>> =
        request {
            val options = listRoutes().mapNotNull { route ->
                val name = route.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TunnelRouteOption(TunnelRouteName(name), route.description.trim())
            }.distinctBy { it.name }
            val default = options.firstOrNull { it.name == TunnelRouteName.DEFAULT }
            requireNotNull(default) { "Tunnel routes must include default" }
            listOf(default) + options.filterNot { it.name == TunnelRouteName.DEFAULT }
        }

    // This SDK boundary converts unexpected implementation failures into the app's stable failure taxonomy.
    @Suppress("TooGenericExceptionCaught")
    // `UNAVAILABLE_VALUE` only means an ineligible proxy when the write was a route change;
    // for any other setting it stays a plain API rejection.
    private suspend fun <T> request(
        routeChange: Boolean = false,
        block: suspend () -> T,
    ): AccountSettingsRepositoryResult<T> =
        try {
            AccountSettingsRepositoryResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            AccountSettingsRepositoryResult.Failure(error.toAccountSettingsFailure(routeChange))
        } catch (unexpected: Exception) {
            AccountSettingsRepositoryResult.Failure(AccountSettingsFailure.Putio(PutioFailure.Unexpected(unexpected)))
        }
}

internal suspend fun AccountSettingsRepository.execute(effect: AccountSettingsEffect): AccountSettingsEvent =
    when (effect) {
        is AccountSettingsEffect.Load ->
            when (val result = load()) {
                is AccountSettingsRepositoryResult.Success ->
                    AccountSettingsEvent.LoadSucceeded(effect.requestId, result.value)
                is AccountSettingsRepositoryResult.Failure ->
                    AccountSettingsEvent.LoadFailed(effect.requestId, result.failure)
            }

        is AccountSettingsEffect.Save ->
            when (val result = save(effect.change)) {
                is AccountSettingsRepositoryResult.Success ->
                    AccountSettingsEvent.SaveSucceeded(effect.requestId)
                is AccountSettingsRepositoryResult.Failure ->
                    AccountSettingsEvent.SaveFailed(effect.requestId, result.failure)
            }

        is AccountSettingsEffect.Refresh ->
            when (val result = load()) {
                is AccountSettingsRepositoryResult.Success ->
                    AccountSettingsEvent.RefreshSucceeded(effect.requestId, result.value)
                is AccountSettingsRepositoryResult.Failure ->
                    AccountSettingsEvent.RefreshFailed(effect.requestId, result.failure)
            }
    }

private fun AccountSettings.toPreferences(): AccountSettingsPreferences =
    AccountSettingsPreferences(
        historyEnabled = historyEnabled,
        trashEnabled = trashEnabled,
        showSubtitles = !hideSubtitles,
        autoSelectSubtitles = !dontAutoselectSubtitles,
        resumePlayback = useStartFrom,
        tunnelRoute = TunnelRouteName.fromServer(tunnelRouteName),
        defaultSort = FilesSort.fromApiValue(sortBy),
        diagnosticsEnabled = diagnosticsEnabled,
        productAnalyticsEnabled = productAnalyticsEnabled,
        supportWidgetEnabled = supportWidgetEnabled,
    )

private fun AccountSettingsChange.toPatch(): AccountSettingsPatch =
    when (this) {
        is AccountSettingsChange.Route -> AccountSettingsPatch(tunnelRouteName = name.value)
        is AccountSettingsChange.Sort -> AccountSettingsPatch(sortBy = sort.apiValue)
        is AccountSettingsChange.Toggle -> when (key) {
            AccountSettingsKey.History -> AccountSettingsPatch(historyEnabled = enabled)
            AccountSettingsKey.Trash -> AccountSettingsPatch(trashEnabled = enabled)
            AccountSettingsKey.ShowSubtitles -> AccountSettingsPatch(hideSubtitles = !enabled)
            AccountSettingsKey.AutoSelectSubtitles -> AccountSettingsPatch(dontAutoselectSubtitles = !enabled)
            AccountSettingsKey.ResumePlayback -> AccountSettingsPatch(useStartFrom = enabled)
            AccountSettingsKey.Diagnostics -> AccountSettingsPatch(diagnosticsEnabled = enabled)
            AccountSettingsKey.ProductAnalytics -> AccountSettingsPatch(productAnalyticsEnabled = enabled)
            AccountSettingsKey.SupportWidget -> AccountSettingsPatch(supportWidgetEnabled = enabled)
            AccountSettingsKey.TunnelRoute,
            AccountSettingsKey.DefaultSort,
            -> error("$key is not a toggle")
        }
    }

private fun PutioException.toAccountSettingsFailure(routeChange: Boolean): AccountSettingsFailure =
    if (routeChange && findPutioApiException()?.errorType == UNAVAILABLE_VALUE_ERROR_TYPE) {
        AccountSettingsFailure.RouteUnavailable(this)
    } else {
        AccountSettingsFailure.Putio(toSettingsFailure())
    }

private const val UNAVAILABLE_VALUE_ERROR_TYPE = "UNAVAILABLE_VALUE"
