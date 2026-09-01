package io.putdotio.android.settings

import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.account.AccountSettingsPatch
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

            assertEquals(AccountSettingsPatch(historyEnabled = false), patches[0])
            assertEquals(AccountSettingsPatch(trashEnabled = true), patches[1])
            assertEquals(AccountSettingsPatch(hideSubtitles = false), patches[2])
            assertEquals(AccountSettingsPatch(dontAutoselectSubtitles = false), patches[3])
        }

    @Test
    fun saveReturnsSettingsFromTheAuthoritativeReload() =
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

            val result =
                repository.save(
                    AccountSettingsChange(AccountSettingsKey.History, enabled = false),
                ) as AccountSettingsRepositoryResult.Success

            assertEquals(listOf("save", "read"), operations)
            assertEquals(
                AccountSettingsPreferences(
                    historyEnabled = false,
                    trashEnabled = true,
                    showSubtitles = false,
                    autoSelectSubtitles = false,
                ),
                result.value,
            )
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
    ): SdkAccountSettingsRepository =
        SdkAccountSettingsRepository(
            getSettings = {
                getError?.let { throw it }
                settings
            },
            saveSettings = onSave,
        )

    private companion object {
        val Settings =
            AccountSettings(
                sortBy = "NAME_ASC",
                historyEnabled = true,
                trashEnabled = false,
                hideSubtitles = true,
                dontAutoselectSubtitles = true,
            )
    }
}
