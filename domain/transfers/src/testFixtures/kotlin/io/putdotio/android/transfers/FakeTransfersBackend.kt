package io.putdotio.android.transfers

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioKnownErrorContract
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransferStatus
import io.putdotio.sdk.transfers.TransferType
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Serves a history the way the put.io API does: `/transfers/list` returns the newest
 * 10,000 transfers only, newest first, and continuation pages follow the cursor. Each
 * response is JSON-encoded once and decoded per request, as the SDK transport would.
 * JVM tests count its requests and rows; device benchmarks replay it for CPU time.
 */
class FakeTransfersBackend(historySize: Int, activePositions: Set<Int>) {
    val history: List<Transfer> =
        (0 until historySize).map { position ->
            historicalTransfer(id = (historySize - position).toLong(), active = position in activePositions)
        }
    private val listable = history.take(LIST_CAP)
    private val byId = history.associateBy(Transfer::id)
    private val encodedPages = mutableMapOf<Pair<Int, Int>, String>()
    private val encodedRows = mutableMapOf<Long, String>()
    var requests = 0
        private set
    var rowsDecoded = 0
        private set

    internal fun reads(): TransfersReadOperations =
        TransfersReadOperations(
            list = { query -> page(0, requireNotNull(query.perPage)) },
            continueList = { cursor, query -> page(cursor.toInt(), requireNotNull(query.perPage)) },
            get = ::get,
        )

    fun repository(): SdkTransfersRepository =
        SdkTransfersRepository(
            reads(),
            adds = TransfersAddOperations({ error("unused") }, { error("unused") }, { error("unused") }),
            cancelTransfers = { error("unused") },
            retryTransfer = { error("unused") },
            cleanTransfers = { TransfersCleanResponse(emptyList(), "OK") },
        )

    private fun page(offset: Int, perPage: Int): TransfersListResponse {
        val body =
            encodedPages.getOrPut(offset to perPage) {
                val rows = listable.drop(offset).take(perPage)
                val next = offset + rows.size
                val cursor = if (next < listable.size) next.toString() else null
                json.encodeToString(
                    TransfersListResponse.serializer(),
                    TransfersListResponse(cursor = cursor, transfers = rows, status = "OK"),
                )
            }
        return decode(body, TransfersListResponse.serializer()).also { rowsDecoded += it.transfers.size }
    }

    private fun get(id: Long): Transfer {
        val transfer = byId[id]
        if (transfer == null) {
            requests += 1
            throw notFound(id)
        }
        val body = encodedRows.getOrPut(id) { json.encodeToString(Transfer.serializer(), transfer) }
        return decode(body, Transfer.serializer()).also { rowsDecoded += 1 }
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T {
        requests += 1
        return json.decodeFromString(serializer, body)
    }

    private companion object {
        const val LIST_CAP = 10_000
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

private fun notFound(id: Long): PutioOperationException {
    val url = "https://api.put.io/v2/transfers/$id"
    return PutioOperationException(
        domain = "transfers",
        operation = "get",
        contract = PutioKnownErrorContract(statusCode = 404),
        reason = PutioOperationErrorReason.StatusCode(404),
        underlyingError =
            PutioApiException(
                request = PutioRequestData("GET", url),
                resolvedStatusCode = 404,
                resolvedErrorType = null,
                envelope = PutioApiErrorEnvelope(statusCode = 404),
                responseBody = "{}",
                message = "Not Found",
            ),
    )
}

internal fun historicalTransfer(id: Long, active: Boolean): Transfer =
    Transfer(
        id = id,
        name = "Example.Distribution.$id.x86_64.DVD.iso",
        source =
            "magnet:?xt=urn:btih:${"%040x".format(id)}&dn=Example.Distribution.$id" +
                "&tr=udp%3A%2F%2Ftracker.example.invalid%3A1337%2Fannounce",
        type = TransferType.TORRENT,
        status = if (active) TransferStatus.DOWNLOADING else TransferStatus.COMPLETED,
        fileId = if (active) null else id + FILE_ID_OFFSET,
        downloadId = id + DOWNLOAD_ID_OFFSET,
        size = 4.7e9,
        percentDone = if (active) 42.0 else 100.0,
        completionPercent = if (active) 0.0 else 100.0,
        downloaded = 4.7e9,
        uploaded = 1.2e9,
        downSpeed = if (active) 1.5e6 else 0.0,
        upSpeed = 0.0,
        availability = 100.0,
        createdAt = "2024-01-01T00:00:00",
        startedAt = "2024-01-01T00:00:05",
        finishedAt = if (active) null else "2024-01-01T01:00:00",
        currentRatio = 0.25,
        secondsSeeding = 0.0,
        userFileExists = !active,
    )

private const val FILE_ID_OFFSET = 1_000_000_000L
private const val DOWNLOAD_ID_OFFSET = 2_000_000_000L
