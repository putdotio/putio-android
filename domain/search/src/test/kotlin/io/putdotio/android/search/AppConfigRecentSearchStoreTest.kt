package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.config.AppConfig
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
        runBlocking {
            val saved = mutableListOf<List<String>>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = listOf(" existing ", "existing", "")) },
                    saveTerms = { saved += it },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                store.awaitTerms("existing")
                (1..6).forEach { store.record(SearchTerm("term-$it")) }
                store.record(SearchTerm("term-4"))

                store.awaitTerms("term-4", "term-6", "term-5", "term-3", "term-2")
                withTimeout(TIMEOUT) {
                    while (saved.size < 7) delay(1)
                }
                assertEquals(
                    listOf("term-4", "term-6", "term-5", "term-3", "term-2"),
                    saved.last(),
                )
            } finally {
                store.close()
            }
        }

    @Test
    fun removesAndClearsServerTerms() =
        runBlocking {
            val saved = mutableListOf<List<String>>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = listOf("one", "two")) },
                    saveTerms = { saved += it },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                store.awaitTerms("one", "two")
                store.remove(SearchTerm("one"))
                store.awaitTerms("two")
                store.clear()
                store.awaitTerms()

                withTimeout(TIMEOUT) {
                    while (saved.size < 2) delay(1)
                }
                assertEquals(listOf(listOf("two"), emptyList()), saved)
            } finally {
                store.close()
            }
        }

    @Test
    fun disabledServerSettingNeitherLoadsNorRecordsTerms() =
        runBlocking {
            val loaded = CompletableDeferred<Unit>()
            val saved = mutableListOf<List<String>>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = {
                        loaded.complete(Unit)
                        RecentSearchConfig(enabled = false, terms = listOf("private"))
                    },
                    saveTerms = { saved += it },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                withTimeout(TIMEOUT) { loaded.await() }
                store.record(SearchTerm("ignored"))
                delay(50)

                assertEquals(emptyList<SearchTerm>(), store.terms.value)
                assertEquals(emptyList<List<String>>(), saved)
            } finally {
                store.close()
            }
        }

    @Test
    fun disablingClearsTheTermsFirstAndEnablingTurnsRecordingBackOn() =
        runBlocking {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = listOf("one")))
            val writes = server.writes
            val store = server.store(this)

            try {
                store.awaitTerms("one")
                assertEquals(true, store.enabled.value)

                store.setEnabled(false)
                withTimeout(TIMEOUT) { store.enabled.first { it == false } }
                store.awaitTerms()
                store.record(SearchTerm("ignored"))
                store.setEnabled(true)
                withTimeout(TIMEOUT) { store.enabled.first { it == true } }
                store.record(SearchTerm("two"))
                store.awaitTerms("two")

                withTimeout(TIMEOUT) {
                    while (writes.size < 4) delay(1)
                }
                assertEquals(
                    listOf(
                        "$SEARCH_HISTORY_KEY=[]",
                        "$SEARCH_HISTORY_ENABLED_KEY=false",
                        "$SEARCH_HISTORY_ENABLED_KEY=true",
                        "$SEARCH_HISTORY_KEY=[two]",
                    ),
                    writes,
                )
            } finally {
                store.close()
            }
        }

    @Test
    fun disablingClearsTermsAnotherClientRecordedAfterTheLoad() =
        runBlocking {
            val server = FakeConfigServer(RecentSearchConfig(enabled = true, terms = emptyList()))
            val store = server.store(this)

            try {
                withTimeout(TIMEOUT) { store.enabled.first { it == true } }
                server.config = server.config.copy(terms = listOf("from tv"))

                store.setEnabled(false)

                withTimeout(TIMEOUT) {
                    while (server.writes.size < 2) delay(1)
                }
                assertEquals(
                    listOf("$SEARCH_HISTORY_KEY=[]", "$SEARCH_HISTORY_ENABLED_KEY=false"),
                    server.writes,
                )
                assertEquals(RecentSearchConfig(enabled = false, terms = emptyList()), server.config)
            } finally {
                store.close()
            }
        }

    @Test
    fun enablingKeepsTermsAnotherClientRecordedAfterTheLoad() =
        runBlocking {
            val server = FakeConfigServer(RecentSearchConfig(enabled = false, terms = emptyList()))
            val store = server.store(this)

            try {
                withTimeout(TIMEOUT) { store.enabled.first { it == false } }
                server.config = RecentSearchConfig(enabled = true, terms = listOf("from tv"))

                store.setEnabled(true)
                store.awaitTerms("from tv")
                store.record(SearchTerm("two"))
                store.awaitTerms("two", "from tv")

                withTimeout(TIMEOUT) {
                    while (server.writes.isEmpty()) delay(1)
                }
                assertEquals(listOf("$SEARCH_HISTORY_KEY=[two, from tv]"), server.writes)
            } finally {
                store.close()
            }
        }

    @Test
    fun anAccountThatTurnedHistoryOffElsewhereCanTurnItBackOn() =
        runBlocking {
            val savedEnabled = mutableListOf<Boolean>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = false, terms = emptyList()) },
                    saveTerms = { error("Nothing to clear") },
                    saveEnabled = { savedEnabled += it },
                    parentScope = this,
                )

            try {
                withTimeout(TIMEOUT) { store.enabled.first { it == false } }

                store.setEnabled(true)

                withTimeout(TIMEOUT) { store.enabled.first { it == true } }
                withTimeout(TIMEOUT) {
                    while (savedEnabled.isEmpty()) delay(1)
                }
                assertEquals(listOf(true), savedEnabled)
            } finally {
                store.close()
            }
        }

    @Test
    fun aFailedSettingWriteRollsBackAndRetries() =
        runBlocking {
            var attempts = 0
            val firstAttempt = CompletableDeferred<Unit>()
            val savedEnabled = mutableListOf<Boolean>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = emptyList()) },
                    saveTerms = { error("Nothing to clear") },
                    saveEnabled = {
                        attempts += 1
                        if (attempts == 1) {
                            firstAttempt.complete(Unit)
                            error("offline")
                        }
                        savedEnabled += it
                    },
                    parentScope = this,
                )

            try {
                withTimeout(TIMEOUT) { store.enabled.first { it == true } }
                store.setEnabled(false)
                withTimeout(TIMEOUT) { firstAttempt.await() }
                store.failure.first { it != null }
                assertEquals(true, store.enabled.value)

                store.retry()

                withTimeout(TIMEOUT) { store.enabled.first { it == false } }
                withTimeout(TIMEOUT) {
                    while (savedEnabled.isEmpty()) delay(1)
                }
                assertEquals(listOf(false), savedEnabled)
                assertEquals(null, store.failure.value)
            } finally {
                store.close()
            }
        }

    @Test
    fun retriesInitialConfigFailureAndAppliesPendingEdit() =
        runBlocking {
            val firstLoadFailed = CompletableDeferred<Unit>()
            var loadCalls = 0
            val saved = mutableListOf<List<String>>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = {
                        loadCalls += 1
                        if (loadCalls == 1) {
                            firstLoadFailed.complete(Unit)
                            error("offline")
                        }
                        RecentSearchConfig(enabled = true, terms = listOf("existing"))
                    },
                    saveTerms = { saved += it },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                withTimeout(TIMEOUT) { firstLoadFailed.await() }
                store.record(SearchTerm("new"))

                store.awaitTerms("new", "existing")
                withTimeout(TIMEOUT) {
                    while (saved.isEmpty()) delay(1)
                }
                assertEquals(2, loadCalls)
                assertEquals(listOf("new", "existing"), saved.single())
                assertEquals(null, store.failure.value)
            } finally {
                store.close()
            }
        }

    @Test
    fun explicitlyRetriesInitialConfigFailureWithoutAnEdit() =
        runBlocking {
            val firstLoadFailed = CompletableDeferred<Unit>()
            var loadCalls = 0
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = {
                        loadCalls += 1
                        if (loadCalls == 1) {
                            firstLoadFailed.complete(Unit)
                            error("offline")
                        }
                        RecentSearchConfig(enabled = true, terms = listOf("existing"))
                    },
                    saveTerms = { error("No edit should be saved") },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                withTimeout(TIMEOUT) { firstLoadFailed.await() }
                store.failure.first { it != null }

                store.retry()

                store.awaitTerms("existing")
                assertEquals(2, loadCalls)
                assertEquals(null, store.failure.value)
            } finally {
                store.close()
            }
        }

    @Test
    fun failedServerWriteRollsBackTheOptimisticEdit() =
        runBlocking {
            val attempted = CompletableDeferred<Unit>()
            val failure = IllegalStateException("offline")
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = listOf("one")) },
                    saveTerms = {
                        attempted.complete(Unit)
                        throw failure
                    },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                store.awaitTerms("one")
                store.record(SearchTerm("two"))
                withTimeout(TIMEOUT) { attempted.await() }
                withTimeout(TIMEOUT) {
                    store.terms.first { it == listOf(SearchTerm("one")) }
                }
                assertEquals(listOf(SearchTerm("one")), store.terms.value)
                assertSame(failure, (store.failure.value as PutioFailure.Unexpected).cause)
            } finally {
                store.close()
            }
        }

    @Test
    fun retainsFailedWriteAndRetriesItBeforeTheNextEdit() =
        runBlocking {
            var attempts = 0
            val saved = mutableListOf<List<String>>()
            val firstAttempt = CompletableDeferred<Unit>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = listOf("one")) },
                    saveTerms = {
                        attempts += 1
                        if (attempts == 1) {
                            firstAttempt.complete(Unit)
                            error("offline")
                        }
                        saved += it
                    },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                store.awaitTerms("one")
                store.record(SearchTerm("two"))
                withTimeout(TIMEOUT) { firstAttempt.await() }
                store.awaitTerms("one")

                store.record(SearchTerm("three"))

                store.awaitTerms("three", "two", "one")
                withTimeout(TIMEOUT) {
                    while (saved.size < 2) delay(1)
                }
                assertEquals(
                    listOf(listOf("two", "one"), listOf("three", "two", "one")),
                    saved,
                )
                assertEquals(null, store.failure.value)
            } finally {
                store.close()
            }
        }

    @Test
    fun explicitlyRetriesTheRetainedFailedWrite() =
        runBlocking {
            var attempts = 0
            val saved = mutableListOf<List<String>>()
            val firstAttempt = CompletableDeferred<Unit>()
            val store =
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = listOf("one")) },
                    saveTerms = {
                        attempts += 1
                        if (attempts == 1) {
                            firstAttempt.complete(Unit)
                            error("offline")
                        }
                        saved += it
                    },
                    saveEnabled = { error("No setting change") },
                    parentScope = this,
                )

            try {
                store.awaitTerms("one")
                store.record(SearchTerm("two"))
                withTimeout(TIMEOUT) { firstAttempt.await() }
                store.failure.first { it != null }
                store.awaitTerms("one")

                store.retry()

                store.awaitTerms("two", "one")
                withTimeout(TIMEOUT) {
                    while (saved.isEmpty()) delay(1)
                }
                assertEquals(listOf(listOf("two", "one")), saved)
                assertEquals(null, store.failure.value)
            } finally {
                store.close()
            }
        }

    private suspend fun AppConfigRecentSearchStore.awaitTerms(vararg expected: String) {
        val terms = expected.map(::SearchTerm)
        withTimeout(TIMEOUT) { this@awaitTerms.terms.first { it == terms } }
    }

    /** `/config` as the store and other clients see it: each save lands, each load reads it. */
    private class FakeConfigServer(
        @Volatile var config: RecentSearchConfig,
    ) {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun store(scope: CoroutineScope) =
            AppConfigRecentSearchStore(
                loadConfig = { config },
                saveTerms = {
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
