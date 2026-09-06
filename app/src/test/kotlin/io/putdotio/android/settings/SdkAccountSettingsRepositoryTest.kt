package io.putdotio.android.settings

import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.account.AccountSettingsPatch
import io.putdotio.sdk.routes.TunnelRoute
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdkAccountSettingsRepositoryTest {

    @Test
    fun loadsOnlyTheAccountWideSettingsOwnedByThisSlice() =
        runBlocking {
            val repository = repository(settings = Settings)

            val result = repository.load() as AccountSettingsRepositoryResult.Success

            assertEquals(
                AccountSettingsPreferences(
                    historyEnabled = true,
                    trashEnabled = false,
                    showSubtitles = false,
                    autoSelectSubtitles = false,
                    resumePlayback = false,
                ),
                result.value,
            )
        }

    @Test
    fun mapsUiSemanticsToExactAccountSettingsPatchKeys() =
        runBlocking {
            val patches = mutableListOf<AccountSettingsPatch>()
            val repository = repository(onSave = patches::add)

            repository.save(AccountSettingsChange(AccountSettingsKey.History, false))
            repository.save(AccountSettingsChange(AccountSettingsKey.Trash, true))
            repository.save(AccountSettingsChange(AccountSettingsKey.ShowSubtitles, true))
            repository.save(AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, true))
            repository.save(AccountSettingsChange(AccountSettingsKey.ResumePlayback, false))

            assertEquals(AccountSettingsPatch(historyEnabled = false), patches[0])
            assertEquals(AccountSettingsPatch(trashEnabled = true), patches[1])
            assertEquals(AccountSettingsPatch(hideSubtitles = false), patches[2])
            assertEquals(AccountSettingsPatch(dontAutoselectSubtitles = false), patches[3])
            assertEquals(AccountSettingsPatch(useStartFrom = false), patches[4])
        }

    @Test
    fun acceptedSaveAndAuthoritativeRefreshRemainSeparateEffects() =
        runBlocking {
            val operations = mutableListOf<String>()
            val reloadedSettings = Settings.copy(historyEnabled = false, trashEnabled = true)
            val repository =
                SdkAccountSettingsRepository(
                    getSettings = {
                        operations += "read"
                        reloadedSettings
                    },
                    saveSettings = {
                        operations += "save"
                    },
                )

            val requestId = AccountSettingsRequestId(7L)
            val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)
            val saveEvent = repository.execute(AccountSettingsEffect.Save(requestId, change))

            assertEquals(listOf("save"), operations)
            assertEquals(AccountSettingsEvent.SaveSucceeded(requestId), saveEvent)

            val refreshEvent = repository.execute(AccountSettingsEffect.Refresh(requestId))

            assertEquals(listOf("save", "read"), operations)
            assertEquals(
                AccountSettingsEvent.RefreshSucceeded(
                    requestId,
                    AccountSettingsPreferences(
                        historyEnabled = false,
                        trashEnabled = true,
                        showSubtitles = false,
                        autoSelectSubtitles = false,
                        resumePlayback = false,
                    ),
                ),
                refreshEvent,
            )
        }

    @Test
    fun failedRefreshAfterAcceptedSaveDoesNotReportTheWriteAsFailed() =
        runBlocking {
            var saveCount = 0
            val refreshFailure = IllegalStateException("reload failed")
            val repository =
                SdkAccountSettingsRepository(
                    getSettings = { throw refreshFailure },
                    saveSettings = { saveCount += 1 },
                )
            val requestId = AccountSettingsRequestId(8L)
            val change = AccountSettingsChange(AccountSettingsKey.Trash, enabled = true)

            val saveEvent = repository.execute(AccountSettingsEffect.Save(requestId, change))
            val refreshEvent = repository.execute(AccountSettingsEffect.Refresh(requestId))

            assertEquals(AccountSettingsEvent.SaveSucceeded(requestId), saveEvent)
            assertTrue(refreshEvent is AccountSettingsEvent.RefreshFailed)
            val refreshError =
                (refreshEvent as AccountSettingsEvent.RefreshFailed).failure as AccountSettingsFailure.Unexpected
            assertSame(
                refreshFailure,
                refreshError.cause,
            )
            assertEquals(1, saveCount)
        }

    @Test
    fun classifiesAuthoritativeAuthenticationFailures() =
        runBlocking {
            val apiError =
                PutioApiException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/account/settings"),
                    resolvedStatusCode = 401,
                    resolvedErrorType = "invalid_token",
                    envelope = PutioApiErrorEnvelope(statusCode = 401, errorType = "invalid_token"),
                    responseBody = "{}",
                    message = "Request rejected",
                )
            val operationError =
                PutioOperationException(
                    domain = "account",
                    operation = "getSettings",
                    contract = null,
                    reason = PutioOperationErrorReason.StatusCode(401),
                    underlyingError = apiError,
                )
            val repository = repository(getError = operationError)

            val result = repository.load() as AccountSettingsRepositoryResult.Failure

            assertTrue(result.failure is AccountSettingsFailure.AuthenticationRequired)
            assertSame(operationError, result.failure.cause)
        }

    @Test
    fun keepsInvalidScopeAsAccessDeniedWithoutExpiringTheSession() =
        runBlocking {
            val apiError =
                PutioApiException(
                    request = PutioRequestData("POST", "https://api.put.io/v2/account/settings"),
                    resolvedStatusCode = 401,
                    resolvedErrorType = "invalid_scope",
                    envelope = PutioApiErrorEnvelope(statusCode = 401, errorType = "invalid_scope"),
                    responseBody = "{}",
                    message = "Request rejected",
                )
            val operationError =
                PutioOperationException(
                    domain = "account",
                    operation = "saveSettings",
                    contract = null,
                    reason = PutioOperationErrorReason.StatusCode(401),
                    underlyingError = apiError,
                )
            val repository = repository(getError = operationError)

            val result = repository.load() as AccountSettingsRepositoryResult.Failure

            assertTrue(result.failure is AccountSettingsFailure.AccessDenied)
            assertSame(operationError, result.failure.cause)
        }

    @Test
    fun boundsUnexpectedFailuresAndPreservesCancellation() {
        val unexpected = IllegalStateException("broken mapper")
        val result = runBlocking { repository(getError = unexpected).load() } as AccountSettingsRepositoryResult.Failure
        assertSame(unexpected, (result.failure as AccountSettingsFailure.Unexpected).cause)

        val cancellation = CancellationException("screen closed")
        try {
            runBlocking { repository(getError = cancellation).load() }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun repository(
        settings: AccountSettings = Settings,
        getError: Throwable? = null,
        onSave: (AccountSettingsPatch) -> Unit = {},
        routes: suspend () -> List<TunnelRoute> = { error("Routes are not expected") },
    ): SdkAccountSettingsRepository =
        SdkAccountSettingsRepository(
            getSettings = {
                getError?.let { throw it }
                settings
            },
            saveSettings = onSave,
            listRoutes = routes,
        )

    @Test
    fun mapsTunnelRouteFromServerAndPatchesTheExactName() =
        runBlocking {
            val direct = repository(settings = Settings.copy(tunnelRouteName = null)).load()
            assertEquals(TunnelRouteName.DEFAULT,
                (direct as AccountSettingsRepositoryResult.Success).value.tunnelRoute)
            val cdn = repository(settings = Settings.copy(tunnelRouteName = " cdn77 ")).load()
            assertEquals(TunnelRouteName("cdn77"), (cdn as AccountSettingsRepositoryResult.Success).value.tunnelRoute)

            val patches = mutableListOf<AccountSettingsPatch>()
            val repository = repository(onSave = patches::add)
            repository.save(AccountSettingsChange.Route(TunnelRouteName("cdn77")))
            repository.save(AccountSettingsChange.Route(TunnelRouteName.DEFAULT))
            assertEquals(
                listOf(
                    AccountSettingsPatch(tunnelRouteName = "cdn77"),
                    AccountSettingsPatch(tunnelRouteName = "default"),
                ),
                patches,
            )
        }

    @Test
    fun tunnelRoutesKeepServerOrderDropBlankNamesAndRequireDefault() =
        runBlocking {
            val loaded = repository(routes = {
                listOf(
                    TunnelRoute("default", "Amsterdam (Direct)"),
                    TunnelRoute("cdn77", " CDN "),
                    TunnelRoute("  ", "blank"),
                    TunnelRoute("cdn77", "duplicate"),
                )
            }).loadTunnelRoutes() as AccountSettingsRepositoryResult.Success
            assertEquals(
                listOf(
                    TunnelRouteOption(TunnelRouteName.DEFAULT, "Amsterdam (Direct)"),
                    TunnelRouteOption(TunnelRouteName("cdn77"), "CDN"),
                ),
                loaded.value,
            )
            val missingDefault = repository(routes = { listOf(TunnelRoute("cdn77", "CDN")) }).loadTunnelRoutes()
            assertTrue(missingDefault is AccountSettingsRepositoryResult.Failure)
            val cause = PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/tunnel/routes"),
                resolvedStatusCode = 401,
                resolvedErrorType = "invalid_token",
                envelope = PutioApiErrorEnvelope(statusCode = 401, errorType = "invalid_token"),
                responseBody = "{}",
                message = "Request rejected",
            )
            val denied = repository(routes = { throw cause }).loadTunnelRoutes()
                as AccountSettingsRepositoryResult.Failure
            assertTrue(denied.failure is AccountSettingsFailure.AuthenticationRequired)
        }

    private companion object {
        val Settings =
            AccountSettings(
                sortBy = "NAME_ASC",
                historyEnabled = true,
                trashEnabled = false,
                hideSubtitles = true,
                dontAutoselectSubtitles = true,
                useStartFrom = false,
            )
    }
}
