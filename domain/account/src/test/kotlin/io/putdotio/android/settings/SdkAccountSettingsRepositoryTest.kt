package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesSort
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.account.AccountSettingsPatch
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.routes.TunnelRoute
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
        runBlocking<Unit> {
            val repository = repository(settings = Settings.copy(nextEpisode = true))

            val result = repository.load() as AccountSettingsRepositoryResult.Success

            assertEquals(
                AccountSettingsPreferences(
                    historyEnabled = true,
                    trashEnabled = false,
                    showSubtitles = false,
                    autoSelectSubtitles = false,
                    resumePlayback = false,
                    defaultSort = FilesSort.NAME_ASCENDING,
                    nextEpisode = true,
                ),
                result.value,
            )
        }

    @Test
    fun mapsUiSemanticsToExactAccountSettingsPatchKeys() =
        runBlocking<Unit> {
            val patches = mutableListOf<AccountSettingsPatch>()
            val repository = repository(onSave = patches::add)

            repository.save(AccountSettingsChange(AccountSettingsKey.History, false))
            repository.save(AccountSettingsChange(AccountSettingsKey.Trash, true))
            repository.save(AccountSettingsChange(AccountSettingsKey.ShowSubtitles, true))
            repository.save(AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, true))
            repository.save(AccountSettingsChange(AccountSettingsKey.ResumePlayback, false))
            repository.save(AccountSettingsChange(AccountSettingsKey.Diagnostics, false))
            repository.save(AccountSettingsChange(AccountSettingsKey.ProductAnalytics, true))

            assertEquals(AccountSettingsPatch(historyEnabled = false), patches[0])
            assertEquals(AccountSettingsPatch(trashEnabled = true), patches[1])
            assertEquals(AccountSettingsPatch(hideSubtitles = false), patches[2])
            assertEquals(AccountSettingsPatch(dontAutoselectSubtitles = false), patches[3])
            assertEquals(AccountSettingsPatch(useStartFrom = false), patches[4])
            assertEquals(AccountSettingsPatch(diagnosticsEnabled = false), patches[5])
            assertEquals(AccountSettingsPatch(productAnalyticsEnabled = true), patches[6])
        }

    @Test
    fun acceptedSaveAndAuthoritativeRefreshRemainSeparateEffects() =
        runBlocking<Unit> {
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
                        defaultSort = FilesSort.NAME_ASCENDING,
                    ),
                ),
                refreshEvent,
            )

            val refreshFailure = IllegalStateException("reload failed")
            val failingRefresh =
                SdkAccountSettingsRepository(
                    getSettings = {
                        operations += "read"
                        throw refreshFailure
                    },
                    saveSettings = { operations += "save" },
                )
            assertEquals(
                AccountSettingsEvent.SaveSucceeded(requestId),
                failingRefresh.execute(AccountSettingsEffect.Save(requestId, change)),
            )
            val failedRefresh = failingRefresh.execute(AccountSettingsEffect.Refresh(requestId))
            val refreshError =
                (failedRefresh as AccountSettingsEvent.RefreshFailed).failure.putioFailure as PutioFailure.Unexpected
            assertSame(refreshFailure, refreshError.cause)
            assertEquals(listOf("save", "read", "save", "read"), operations)
        }

    @Test
    fun classifiesAuthoritativeAuthenticationFailures() =
        runBlocking<Unit> {
            val operationError = operationError("getSettings", 401, "invalid_token")
            val repository = repository(getError = operationError)

            val result = repository.load() as AccountSettingsRepositoryResult.Failure

            assertTrue(result.failure.putioFailure is PutioFailure.AuthenticationRequired)
            assertSame(operationError, result.failure.cause)
        }

    @Test
    fun keepsInvalidScopeAsAccessDeniedWithoutExpiringTheSession() =
        runBlocking<Unit> {
            val operationError = operationError("saveSettings", 401, "invalid_scope")
            val repository = repository(getError = operationError)

            val result = repository.load() as AccountSettingsRepositoryResult.Failure

            assertTrue(result.failure.putioFailure is PutioFailure.AccessDenied)
            assertSame(operationError, result.failure.cause)
        }

    @Test
    fun boundsUnexpectedFailuresAndPreservesCancellation() {
        val unexpected = IllegalStateException("broken mapper")
        val result = runBlocking { repository(getError = unexpected).load() } as AccountSettingsRepositoryResult.Failure
        assertSame(unexpected, (result.failure.putioFailure as PutioFailure.Unexpected).cause)

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
        runBlocking<Unit> {
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
    fun mapsPrivacyControlsFromServer() =
        runBlocking<Unit> {
            val optedIn = repository(
                settings = Settings.copy(diagnosticsEnabled = true, productAnalyticsEnabled = true),
            ).load() as AccountSettingsRepositoryResult.Success
            assertTrue(optedIn.value.diagnosticsEnabled)
            assertTrue(optedIn.value.productAnalyticsEnabled)

            val optedOut = repository(
                settings = Settings.copy(diagnosticsEnabled = false, productAnalyticsEnabled = false),
            ).load() as AccountSettingsRepositoryResult.Success
            assertFalse(optedOut.value.diagnosticsEnabled)
            assertFalse(optedOut.value.productAnalyticsEnabled)
        }

    @Test
    fun productAnalyticsIsOptInWhileDiagnosticsStayOnByDefault() {
        assertTrue(DefaultAccountSettingsPreferences.diagnosticsEnabled)
        assertFalse(DefaultAccountSettingsPreferences.productAnalyticsEnabled)
    }

    @Test
    fun mapsDefaultSortFromServerAndPatchesTheExactApiValue() =
        runBlocking<Unit> {
            val unknown = repository(settings = Settings.copy(sortBy = "SOMETHING_NEW")).load()
            assertEquals(null, (unknown as AccountSettingsRepositoryResult.Success).value.defaultSort)
            val known = repository(settings = Settings.copy(sortBy = "DATE_DESC")).load()
            assertEquals(
                FilesSort.DATE_ADDED_DESCENDING,
                (known as AccountSettingsRepositoryResult.Success).value.defaultSort,
            )

            val patches = mutableListOf<AccountSettingsPatch>()
            repository(onSave = patches::add).save(AccountSettingsChange.Sort(FilesSort.WATCH_STATUS_ASCENDING))
            assertEquals(listOf(AccountSettingsPatch(sortBy = "WATCH_ASC")), patches)
        }

    @Test
    fun ineligibleProxyRejectionIsRouteUnavailableWithoutExpiringTheSession() =
        runBlocking<Unit> {
            val operationError = operationError("saveSettings", 403, "UNAVAILABLE_VALUE")
            val repository = repository(onSave = { throw operationError })

            val result = repository.save(AccountSettingsChange.Route(TunnelRouteName("cdn77")))
                as AccountSettingsRepositoryResult.Failure

            val rejected = result.failure as AccountSettingsFailure.RouteUnavailable
            assertSame(operationError, rejected.cause)
        }

    @Test
    fun unavailableValueOnANonRouteSaveIsNotReportedAsAProxyProblem() =
        runBlocking<Unit> {
            val operationError = operationError("saveSettings", 403, "UNAVAILABLE_VALUE")
            val repository = repository(onSave = { throw operationError })

            val result = repository.save(AccountSettingsChange.Sort(FilesSort.WATCH_STATUS_ASCENDING))
                as AccountSettingsRepositoryResult.Failure

            assertFalse(result.failure is AccountSettingsFailure.RouteUnavailable)
            assertTrue(result.failure.putioFailure is PutioFailure.AccessDenied)
            assertSame(operationError, result.failure.cause)
        }

    @Test
    fun tunnelRoutesPutDefaultFirstDropBlankNamesAndRequireDefault() =
        runBlocking<Unit> {
            val loaded = repository(routes = {
                listOf(
                    TunnelRoute("cdn77", " CDN "),
                    TunnelRoute("  ", "blank"),
                    TunnelRoute("default", "Amsterdam (Direct)"),
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
            val cause = apiError(401, "invalid_token")
            val denied = repository(routes = { throw cause }).loadTunnelRoutes()
                as AccountSettingsRepositoryResult.Failure
            assertTrue(denied.failure.putioFailure is PutioFailure.AuthenticationRequired)
        }

    private fun apiError(status: Int, type: String): PutioApiException =
        PutioApiException(
            request = PutioRequestData("POST", "https://api.put.io/v2/account/settings"),
            resolvedStatusCode = status,
            resolvedErrorType = type,
            envelope = PutioApiErrorEnvelope(statusCode = status, errorType = type),
            responseBody = "{}",
            message = "Request rejected",
        )

    private fun operationError(operation: String, status: Int, type: String): PutioOperationException =
        PutioOperationException(
            domain = "account",
            operation = operation,
            contract = null,
            reason = PutioOperationErrorReason.StatusCode(status),
            underlyingError = apiError(status, type),
        )

    private companion object {
        val Settings =
            AccountSettings(
                sortBy = "NAME_ASC",
                historyEnabled = true,
                trashEnabled = false,
                hideSubtitles = true,
                dontAutoselectSubtitles = true,
                useStartFrom = false,
                diagnosticsEnabled = true,
                productAnalyticsEnabled = false,
            )
    }
}
