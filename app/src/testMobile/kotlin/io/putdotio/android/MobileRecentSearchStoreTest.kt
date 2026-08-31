package io.putdotio.android

import io.putdotio.android.search.SearchTerm
import io.putdotio.android.files.FilesFailure
import io.putdotio.sdk.config.AppConfig
import kotlinx.coroutines.CompletableDeferred
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

class MobileRecentSearchStoreTest {
    @Test
    fun parsesAndroidOwnedSearchKeysFromAppConfig() {
        val config =
            AppConfig(
                mapOf(
                    SEARCH_HISTORY_ENABLED_KEY to JsonPrimitive(false),
                    SEARCH_HISTORY_KEY to JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))),
                ),
            )

        assertEquals(MobileSearchConfig(enabled = false, terms = listOf("one", "two")), config.toMobileSearchConfig())
        assertEquals(
            JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))),
            recentSearchConfigUpdate(listOf("one", "two")).value,
        )
        assertEquals(SEARCH_HISTORY_KEY, recentSearchConfigUpdate(emptyList()).key)
    }

    @Test
    fun malformedAndroidSearchConfigFallsBackWithoutAffectingOtherAppValues() {
        val config =
            AppConfig(
                mapOf(
                    SEARCH_HISTORY_ENABLED_KEY to JsonObject(emptyMap()),
                    SEARCH_HISTORY_KEY to JsonPrimitive("not-an-array"),
                    "anotherAndroidSetting" to JsonPrimitive(true),
                ),
            )

        assertEquals(MobileSearchConfig(enabled = true, terms = emptyList()), config.toMobileSearchConfig())
    }

    @Test
    fun loadsServerTermsThenRecordsNewestFirstDeduplicatesAndCapsAtFive() =
        runBlocking {
            val saved = mutableListOf<List<String>>()
            val store =
                MobileRecentSearchStore(
                    loadConfig = { MobileSearchConfig(enabled = true, terms = listOf(" existing ", "existing", "")) },
                    saveTerms = { saved += it },
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
                MobileRecentSearchStore(
                    loadConfig = { MobileSearchConfig(enabled = true, terms = listOf("one", "two")) },
                    saveTerms = { saved += it },
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
                MobileRecentSearchStore(
                    loadConfig = {
                        loaded.complete(Unit)
                        MobileSearchConfig(enabled = false, terms = listOf("private"))
                    },
                    saveTerms = { saved += it },
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
    fun retriesInitialConfigFailureAndAppliesPendingEdit() =
        runBlocking {
            val firstLoadFailed = CompletableDeferred<Unit>()
            var loadCalls = 0
            val saved = mutableListOf<List<String>>()
            val store =
                MobileRecentSearchStore(
                    loadConfig = {
                        loadCalls += 1
                        if (loadCalls == 1) {
                            firstLoadFailed.complete(Unit)
                            error("offline")
                        }
                        MobileSearchConfig(enabled = true, terms = listOf("existing"))
                    },
                    saveTerms = { saved += it },
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
                MobileRecentSearchStore(
                    loadConfig = {
                        loadCalls += 1
                        if (loadCalls == 1) {
                            firstLoadFailed.complete(Unit)
                            error("offline")
                        }
                        MobileSearchConfig(enabled = true, terms = listOf("existing"))
                    },
                    saveTerms = { error("No edit should be saved") },
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
                MobileRecentSearchStore(
                    loadConfig = { MobileSearchConfig(enabled = true, terms = listOf("one")) },
                    saveTerms = {
                        attempted.complete(Unit)
                        throw failure
                    },
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
                assertSame(failure, (store.failure.value as FilesFailure.Unexpected).cause)
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
                MobileRecentSearchStore(
                    loadConfig = { MobileSearchConfig(enabled = true, terms = listOf("one")) },
                    saveTerms = {
                        attempts += 1
                        if (attempts == 1) {
                            firstAttempt.complete(Unit)
                            error("offline")
                        }
                        saved += it
                    },
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
                MobileRecentSearchStore(
                    loadConfig = { MobileSearchConfig(enabled = true, terms = listOf("one")) },
                    saveTerms = {
                        attempts += 1
                        if (attempts == 1) {
                            firstAttempt.complete(Unit)
                            error("offline")
                        }
                        saved += it
                    },
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

    private suspend fun MobileRecentSearchStore.awaitTerms(vararg expected: String) {
        val terms = expected.map(::SearchTerm)
        withTimeout(TIMEOUT) { this@awaitTerms.terms.first { it == terms } }
    }

    private companion object {
        const val TIMEOUT = 2_000L
    }
}
