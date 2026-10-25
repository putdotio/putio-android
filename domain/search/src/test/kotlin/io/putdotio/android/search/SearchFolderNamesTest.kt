package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFolderNamesTest {
    @Test
    fun readsEachParentOnceAndNamesTheRootTheScopeAndResultFoldersWithoutARead() =
        runBlocking<Unit> {
            val reads = mutableListOf<Long>()
            val answer = CompletableDeferred<Unit>()
            val search = MutableStateFlow(ready(SearchScope(FilesItemId(5L), "Movies")))
            val names = SearchFolderNames(
                resolve = { id ->
                    reads += id.value
                    answer.await()
                    PutioResult.Success(file(id.value, parent = 0L, name = "Folder ${id.value}", folder = true))
                },
                search = search,
                parentScope = this,
            )

            names.use {
                search.value = ready(
                    SearchScope(FilesItemId(5L), "Movies"),
                    file(1L, parent = 0L),
                    file(2L, parent = 5L),
                    file(3L, parent = 9L),
                    file(4L, parent = 9L),
                    file(6L, parent = 0L, name = "Shows", folder = true),
                    file(7L, parent = 6L),
                )
                repeat(3) { yield() }
                // The next page brings the same parent while its read is still out, and again after.
                search.value = ready(null, file(3L, parent = 9L), file(8L, parent = 9L))
                repeat(3) { yield() }
                answer.complete(Unit)
                awaitNames(names) { 9L in it }
                search.value = ready(null, file(3L, parent = 9L), file(10L, parent = 9L))
                repeat(3) { yield() }

                assertEquals(listOf(9L), reads)
                assertEquals(
                    mapOf(5L to "Movies", 6L to "Shows", 9L to "Folder 9"),
                    names.names.value.mapKeys { it.key.value },
                )
            }
        }

    @Test
    fun readsAtMostFourFoldersAtOnce() =
        runBlocking<Unit> {
            var running = 0
            var mostAtOnce = 0
            val release = CompletableDeferred<Unit>()
            val search = MutableStateFlow(ready(null))
            val names = SearchFolderNames(
                resolve = { id ->
                    running += 1
                    mostAtOnce = maxOf(mostAtOnce, running)
                    release.await()
                    running -= 1
                    PutioResult.Success(file(id.value, parent = 0L, folder = true))
                },
                search = search,
                parentScope = this,
            )

            names.use {
                search.value = ready(null, *(1L..10L).map { file(100L + it, parent = it) }.toTypedArray())
                repeat(10) { yield() }
                assertEquals(4, mostAtOnce)
                release.complete(Unit)
                awaitNames(names) { it.size == 10 }
                assertEquals(4, mostAtOnce)
            }
        }

    @Test
    fun aFailedReadIsTriedAgainWhenTheNextResultsLand() =
        runBlocking<Unit> {
            var attempts = 0
            val search = MutableStateFlow(ready(null))
            val names = SearchFolderNames(
                resolve = { id ->
                    attempts += 1
                    if (attempts < 3) {
                        PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("offline")))
                    } else {
                        PutioResult.Success(file(id.value, parent = 0L, name = "Series", folder = true))
                    }
                },
                search = search,
                parentScope = this,
            )

            names.use {
                search.value = ready(null, file(1L, parent = 9L))
                repeat(3) { yield() }
                assertEquals(emptyMap<FilesItemId, String>(), names.names.value)

                // A page asked for is no landing.
                val landed = search.value.content as SearchContent.Ready
                val asked = SearchPaging.Loading(FilesCursor("n"), SearchRequestId(2L))
                search.value = search.value.copy(content = landed.copy(paging = asked))
                repeat(3) { yield() }
                assertEquals(1, attempts)
                search.value = ready(null, file(1L, parent = 9L))
                repeat(3) { yield() }
                assertEquals(2, attempts)
                // The same rows searched again land again, though they equal the last ones.
                search.value = ready(null, file(1L, parent = 9L)).copy(query = "again")
                awaitNames(names) { 9L in it }
                assertEquals(3, attempts)
            }
        }

    @Test
    fun aRejectedSessionSurfacesWhileOtherFailuresStayQuiet() =
        runBlocking<Unit> {
            val search = MutableStateFlow(ready(null))
            val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
            val names = SearchFolderNames(
                resolve = { id ->
                    if (id.value == 9L) {
                        PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("offline")))
                    } else {
                        PutioResult.Failure(rejected)
                    }
                },
                search = search,
                parentScope = this,
            )

            names.use {
                search.value = ready(null, file(1L, parent = 9L))
                repeat(3) { yield() }
                assertEquals(null, names.authenticationFailure.value)
                search.value = ready(null, file(2L, parent = 10L))
                withTimeout(TIMEOUT) { names.authenticationFailure.first { it != null } }
                assertEquals(rejected, names.authenticationFailure.value)
            }
        }

    private suspend fun awaitNames(names: SearchFolderNames, predicate: (Set<Long>) -> Boolean) {
        withTimeout(TIMEOUT) { names.names.first { predicate(it.keys.map(FilesItemId::value).toSet()) } }
    }

    private fun ready(scope: SearchScope?, vararg items: FilesItem): SearchState {
        val content = if (items.isEmpty()) {
            SearchContent.Idle
        } else {
            SearchContent.Ready(SearchTerm("term"), items.toList(), SearchPaging.Complete)
        }
        return SearchReducer.start().copy(query = "term", content = content, scope = scope)
    }

    private fun file(id: Long, parent: Long, name: String = "file $id.mkv", folder: Boolean = false) =
        FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(parent),
            name = name,
            type = if (folder) PutioFileType.FOLDER else PutioFileType.VIDEO,
            sizeBytes = 1L,
            createdAt = "2026-08-30T00:00:00Z",
        )

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
