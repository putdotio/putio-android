package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigRecentSearchStoreTest {
    @Test
    fun parsesAndroidOwnedSearchKeysFromAppConfig() {
        val config =
            AppConfig(
                mapOf(
                    SEARCH_HISTORY_ENABLED_KEY to JsonPrimitive(false),
                    SEARCH_HISTORY_KEY to JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))),
                ),
            )

        assertEquals(RecentSearchConfig(enabled = false, terms = listOf("one", "two")), config.toRecentSearchConfig())
        assertEquals(
            JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))),
            recentSearchConfigUpdate(listOf("one", "two")).value,
        )
        assertEquals(SEARCH_HISTORY_KEY, recentSearchConfigUpdate(emptyList()).key)
        assertEquals(SEARCH_HISTORY_ENABLED_KEY, recentSearchEnabledConfigUpdate(false).key)
        assertEquals(JsonPrimitive(false), recentSearchEnabledConfigUpdate(false).value)
    }

    @Test
    fun malformedAndroidSearchConfigFallsBackWithoutAffectingOtherAppValues() {
        val config =
            AppConfig(
                mapOf(
                    SEARCH_HISTORY_ENABLED_KEY to JsonPrimitive("false"),
                    SEARCH_HISTORY_KEY to
                        JsonArray(
                            listOf(
                                JsonPrimitive(true),
                                JsonPrimitive(7),
                                JsonObject(emptyMap()),
                                JsonPrimitive("kept"),
                            ),
                        ),
                    "anotherAndroidSetting" to JsonPrimitive(true),
                ),
            )

        assertEquals(RecentSearchConfig(enabled = true, terms = listOf("kept")), config.toRecentSearchConfig())
    }

    @Test
    fun loadsServerTermsThenRecordsNewestFirstDeduplicatesAndCapsAtFive() =
        runBlocking<Unit> {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = listOf(" existing ", "existing", "")))
            server.store(this).use { store ->
                store.awaitTerms("existing")
                (1..6).forEach { store.record(SearchTerm("term-$it")) }
                store.record(SearchTerm("term-4"))

                store.awaitTerms("term-4", "term-6", "term-5", "term-3", "term-2")
                awaitUntil { server.writes.size >= 7 }
                assertEquals(listOf("term-4", "term-6", "term-5", "term-3", "term-2"), server.config.terms)
            }
        }

    @Test
    fun removesAndClearsServerTerms() =
        runBlocking<Unit> {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = listOf("one", "two")))
            server.store(this).use { store ->
                store.awaitTerms("one", "two")
                store.remove(SearchTerm("one"))
                store.awaitTerms("two")
                store.clear()
                store.awaitTerms()

                awaitUntil { server.writes.size >= 2 }
                assertEquals(listOf("$SEARCH_HISTORY_KEY=[two]", "$SEARCH_HISTORY_KEY=[]"), server.writes)
            }
        }

    @Test
    fun editsApplyToTermsAnotherClientRecordedAfterTheLoad() =
        runBlocking<Unit> {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = listOf("one")))
            server.store(this).use { store ->
                store.awaitTerms("one")
                server.config = server.config.copy(terms = listOf("from tv", "one"))

                store.record(SearchTerm("two"))
                store.awaitTerms("two", "from tv", "one")
                server.config = server.config.copy(terms = listOf("from web") + server.config.terms)
                store.remove(SearchTerm("one"))
                store.awaitTerms("from web", "two", "from tv")

                awaitUntil { server.writes.size >= 2 }
                assertEquals(
                    listOf("$SEARCH_HISTORY_KEY=[two, from tv, one]", "$SEARCH_HISTORY_KEY=[from web, two, from tv]"),
                    server.writes,
                )
            }
        }

    @Test
    fun aMissingConfigScopeIsAccessDeniedNotAnExpiredSession() =
        runBlocking<Unit> {
            val refused = PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/config"),
                resolvedStatusCode = 401,
                httpStatusCode = 401,
                resolvedErrorType = "invalid_scope",
                envelope = PutioApiErrorEnvelope(statusCode = 401, errorType = "invalid_scope"),
                responseBody = "{}",
                message = "Rejected",
            )
            store(loadConfig = { throw refused }).use { store ->
                val failure = withTimeout(TIMEOUT) { store.failure.first { it != null } }
                assertTrue(failure is PutioFailure.AccessDenied)
            }
        }

    @Test
    fun disabledServerSettingNeitherLoadsNorRecordsTerms() =
        runBlocking<Unit> {
            val loaded = CompletableDeferred<Unit>()
            val saved = mutableListOf<List<String>>()
            store(
                loadConfig = {
                    loaded.complete(Unit)
                    RecentSearchConfig(enabled = false, terms = listOf("private"))
                },
                saveTerms = { saved += it },
            ).use { store ->
                withTimeout(TIMEOUT) { loaded.await() }
                store.record(SearchTerm("ignored"))
                delay(50)

                assertEquals(emptyList<SearchTerm>(), store.terms.value)
                assertEquals(emptyList<List<String>>(), saved)
            }
        }

    @Test
    fun disablingClearsTheTermsFirstAndEnablingTurnsRecordingBackOn() =
        runBlocking<Unit> {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = listOf("one")))
            server.store(this).use { store ->
                store.awaitTerms("one")
                assertEquals(true, store.enabled.value)

                store.setEnabled(false)
                store.awaitEnabled(false)
                store.awaitTerms()
                store.record(SearchTerm("ignored"))
                store.setEnabled(true)
                store.awaitEnabled(true)
                store.record(SearchTerm("two"))
                store.awaitTerms("two")

                awaitUntil { server.writes.size >= 4 }
                assertEquals(
                    listOf(
                        "$SEARCH_HISTORY_KEY=[]",
                        "$SEARCH_HISTORY_ENABLED_KEY=false",
                        "$SEARCH_HISTORY_ENABLED_KEY=true",
                        "$SEARCH_HISTORY_KEY=[two]",
                    ),
                    server.writes,
                )
            }
        }

    @Test
    fun disablingClearsTermsAnotherClientRecordedAfterTheLoad() =
        runBlocking<Unit> {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = emptyList()))
            server.store(this).use { store ->
                store.awaitEnabled(true)
                server.config = server.config.copy(terms = listOf("from tv"))

                store.setEnabled(false)

                awaitUntil { server.writes.size >= 2 }
                assertEquals(listOf("$SEARCH_HISTORY_KEY=[]", "$SEARCH_HISTORY_ENABLED_KEY=false"), server.writes)
                assertEquals(RecentSearchConfig(enabled = false, terms = emptyList()), server.config)
            }
        }

    @Test
    fun enablingKeepsTermsAnotherClientRecordedAfterTheLoad() =
        runBlocking<Unit> {
            val server = FakeConfigServer(RecentSearchConfig(enabled = false, terms = emptyList()))
            server.store(this).use { store ->
                store.awaitEnabled(false)
                server.config = RecentSearchConfig(enabled = true, terms = listOf("from tv"))

                store.setEnabled(true)
                store.awaitTerms("from tv")
                store.record(SearchTerm("two"))
                store.awaitTerms("two", "from tv")

                awaitUntil { server.writes.isNotEmpty() }
                assertEquals(listOf("$SEARCH_HISTORY_KEY=[two, from tv]"), server.writes)
            }
        }

    @Test
    fun aFailedSettingWriteRollsBackAndRetries() =
        runBlocking<Unit> {
            var attempts = 0
            val firstAttempt = CompletableDeferred<Unit>()
            val savedEnabled = mutableListOf<Boolean>()
            store(
                loadConfig = { RecentSearchConfig(enabled = true, terms = emptyList()) },
                saveEnabled = {
                    attempts += 1
                    if (attempts == 1) {
                        firstAttempt.complete(Unit)
                        error("offline")
                    }
                    savedEnabled += it
                },
            ).use { store ->
                store.awaitEnabled(true)
                store.setEnabled(false)
                withTimeout(TIMEOUT) { firstAttempt.await() }
                store.failure.first { it != null }
                assertEquals(true, store.enabled.value)

                store.retry()

                store.awaitEnabled(false)
                awaitUntil { savedEnabled.isNotEmpty() }
                assertEquals(listOf(false), savedEnabled)
                assertEquals(null, store.failure.value)
            }
        }

    @Test
    fun retriesInitialConfigFailureAndAppliesPendingEdit() =
        runBlocking<Unit> {
            val firstLoadFailed = CompletableDeferred<Unit>()
            var loadCalls = 0
            val saved = mutableListOf<List<String>>()
            store(
                loadConfig = {
                    loadCalls += 1
                    if (loadCalls == 1) {
                        firstLoadFailed.complete(Unit)
                        error("offline")
                    }
                    RecentSearchConfig(enabled = true, terms = listOf("existing"))
                },
                saveTerms = { saved += it },
            ).use { store ->
                withTimeout(TIMEOUT) { firstLoadFailed.await() }
                store.record(SearchTerm("new"))

                store.awaitTerms("new", "existing")
                awaitUntil { saved.isNotEmpty() }
                assertEquals(listOf("new", "existing"), saved.single())
                assertEquals(null, store.failure.value)
            }
        }

    @Test
    fun explicitlyRetriesInitialConfigFailureWithoutAnEdit() =
        runBlocking<Unit> {
            val firstLoadFailed = CompletableDeferred<Unit>()
            var loadCalls = 0
            store(
                loadConfig = {
                    loadCalls += 1
                    if (loadCalls == 1) {
                        firstLoadFailed.complete(Unit)
                        error("offline")
                    }
                    RecentSearchConfig(enabled = true, terms = listOf("existing"))
                },
            ).use { store ->
                withTimeout(TIMEOUT) { firstLoadFailed.await() }
                store.failure.first { it != null }

                store.retry()

                store.awaitTerms("existing")
                assertEquals(2, loadCalls)
                assertEquals(null, store.failure.value)
            }
        }

    @Test
    fun retainsFailedWriteAndRetriesItBeforeTheNextEdit() =
        runBlocking<Unit> {
            val offline = IllegalStateException("offline")
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = listOf("one")), failNext = offline)
            server.store(this).use { store ->
                store.awaitTerms("one")
                store.record(SearchTerm("two"))
                val failure = withTimeout(TIMEOUT) { store.failure.first { it != null } }
                assertSame(offline, (failure as PutioFailure.Unexpected).cause)
                store.awaitTerms("one")

                store.record(SearchTerm("three"))

                store.awaitTerms("three", "two", "one")
                awaitUntil { server.writes.size >= 2 }
                assertEquals(listOf("$SEARCH_HISTORY_KEY=[two, one]", "$SEARCH_HISTORY_KEY=[three, two, one]"), server.writes)
                assertEquals(null, store.failure.value)
            }
        }

    private fun CoroutineScope.store(
        loadConfig: suspend () -> RecentSearchConfig,
        saveTerms: suspend (List<String>) -> Unit = { error("No terms change") },
        saveEnabled: suspend (Boolean) -> Unit = { error("No setting change") },
    ) = AppConfigRecentSearchStore(loadConfig, saveTerms, saveEnabled, parentScope = this)

    private suspend fun AppConfigRecentSearchStore.awaitTerms(vararg expected: String) {
        val terms = expected.map(::SearchTerm)
        withTimeout(TIMEOUT) { this@awaitTerms.terms.first { it == terms } }
    }

    private suspend fun AppConfigRecentSearchStore.awaitEnabled(expected: Boolean) {
        withTimeout(TIMEOUT) { enabled.first { it == expected } }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(TIMEOUT) { while (!condition()) delay(1) }
    }

    /** `/config` as the store and other clients see it: each save lands, each load reads it. */
    private class FakeConfigServer(
        @Volatile var config: RecentSearchConfig,
        @Volatile var failNext: Throwable? = null,
    ) {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun store(scope: CoroutineScope) =
            AppConfigRecentSearchStore(
                loadConfig = { config },
                saveTerms = {
                    failNext?.let { error ->
                        failNext = null
                        throw error
                    }
                    config = config.copy(terms = it)
                    writes += "$SEARCH_HISTORY_KEY=$it"
                },
                saveEnabled = {
                    config = config.copy(enabled = it)
                    writes += "$SEARCH_HISTORY_ENABLED_KEY=$it"
                },
                parentScope = scope,
            )
    }

    private companion object {
        const val TIMEOUT = 2_000L
    }
}
