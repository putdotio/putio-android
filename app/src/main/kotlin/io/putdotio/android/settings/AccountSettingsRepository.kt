package io.putdotio.android.settings

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.account.AccountSettingsPatch
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.errors.PutioTransportException
import java.util.concurrent.CancellationException

internal sealed interface AccountSettingsRepositoryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AccountSettingsRepositoryResult<T>

    data class Failure(
        val failure: AccountSettingsFailure,
    ) : AccountSettingsRepositoryResult<Nothing>
}

internal sealed interface AccountSettingsFailure {
    val cause: Throwable

    data class AuthenticationRequired(
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class AccessDenied(
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class RateLimited(
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class ServerUnavailable(
        val statusCode: Int,
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class ApiRejected(
        val statusCode: Int,
        val errorType: String?,
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class NetworkUnavailable(
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class InvalidResponse(
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class Misconfigured(
        override val cause: PutioException,
    ) : AccountSettingsFailure

    data class Unexpected(
        override val cause: Throwable,
    ) : AccountSettingsFailure
}

internal interface AccountSettingsRepository {
    suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences>

    suspend fun save(change: AccountSettingsChange): AccountSettingsRepositoryResult<Unit>
}

internal class SdkAccountSettingsRepository(
    private val getSettings: suspend () -> AccountSettings,
    private val saveSettings: suspend (AccountSettingsPatch) -> Unit,
) : AccountSettingsRepository {
    constructor(client: PutioClient) : this(
        getSettings = client.account::getSettings,
        saveSettings = { patch -> client.account.saveSettings(patch) },
    )

    override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> =
        request { getSettings().toPreferences() }

    override suspend fun save(
        change: AccountSettingsChange,
    ): AccountSettingsRepositoryResult<Unit> =
        request {
            saveSettings(change.toPatch())
        }

    // This SDK boundary converts unexpected implementation failures into the app's stable failure taxonomy.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(block: suspend () -> T): AccountSettingsRepositoryResult<T> =
        try {
            AccountSettingsRepositoryResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            AccountSettingsRepositoryResult.Failure(error.toAccountSettingsFailure())
        } catch (unexpected: Exception) {
            AccountSettingsRepositoryResult.Failure(AccountSettingsFailure.Unexpected(unexpected))
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
    )

private fun AccountSettingsChange.toPatch(): AccountSettingsPatch =
    when (key) {
        AccountSettingsKey.History -> AccountSettingsPatch(historyEnabled = enabled)
        AccountSettingsKey.Trash -> AccountSettingsPatch(trashEnabled = enabled)
        AccountSettingsKey.ShowSubtitles -> AccountSettingsPatch(hideSubtitles = !enabled)
        AccountSettingsKey.AutoSelectSubtitles -> AccountSettingsPatch(dontAutoselectSubtitles = !enabled)
    }

private fun PutioException.toAccountSettingsFailure(): AccountSettingsFailure {
    val invalidScope = findPutioApiException()?.errorType == INVALID_SCOPE_ERROR_TYPE
    return if (invalidScope) {
        AccountSettingsFailure.AccessDenied(this)
    } else {
        var current: PutioException = this
        var reasonFailure: AccountSettingsFailure? = null
        val visited = mutableSetOf<PutioException>()
        while (current is PutioOperationException && visited.add(current) && reasonFailure == null) {
            reasonFailure = current.reasonFailure(context = this)
            current = current.underlyingError
        }
        reasonFailure ?: current.leafFailure(context = this)
    }
}

private fun Throwable.findPutioApiException(): PutioApiException? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is PutioApiException) {
            return current
        }
        current = if (current is PutioOperationException) current.underlyingError else current.cause
    }
    return null
}

private fun PutioOperationException.reasonFailure(context: PutioException): AccountSettingsFailure? =
    when ((reason as? PutioOperationErrorReason.StatusCode)?.statusCode) {
        HTTP_UNAUTHORIZED -> AccountSettingsFailure.AuthenticationRequired(context)
        HTTP_FORBIDDEN -> AccountSettingsFailure.AccessDenied(context)
        else -> null
    }

private fun PutioException.leafFailure(context: PutioException): AccountSettingsFailure =
    when (this) {
        is PutioApiException ->
            when (statusCode) {
                HTTP_UNAUTHORIZED -> AccountSettingsFailure.AuthenticationRequired(context)
                HTTP_FORBIDDEN -> AccountSettingsFailure.AccessDenied(context)
                HTTP_TOO_MANY_REQUESTS -> AccountSettingsFailure.RateLimited(context)
                in HTTP_SERVER_ERROR_RANGE -> AccountSettingsFailure.ServerUnavailable(statusCode, context)
                else -> AccountSettingsFailure.ApiRejected(statusCode, errorType, context)
            }

        is PutioTransportException -> AccountSettingsFailure.NetworkUnavailable(context)
        is PutioSerializationException -> AccountSettingsFailure.InvalidResponse(context)
        is PutioConfigurationException -> AccountSettingsFailure.Misconfigured(context)
        is PutioOperationException -> AccountSettingsFailure.Unexpected(context)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val INVALID_SCOPE_ERROR_TYPE = "invalid_scope"
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
