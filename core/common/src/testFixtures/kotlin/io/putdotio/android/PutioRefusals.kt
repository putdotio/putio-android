package io.putdotio.android

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A put.io error response as the SDK's transport decodes it. */
fun putioRefusal(status: Int, body: String, httpStatusCode: Int = status): PutioApiException {
    val envelope = runCatching { lenientJson.decodeFromString(PutioApiErrorEnvelope.serializer(), body) }
        .getOrElse { PutioApiErrorEnvelope(statusCode = status) }
    return PutioApiException(
        request = PutioRequestData("POST", "https://api.put.io/v2/files/rename"),
        resolvedStatusCode = envelope.statusCode ?: status,
        httpStatusCode = httpStatusCode,
        resolvedErrorType = envelope.errorType,
        envelope = envelope,
        responseBody = body,
        message = envelope.errorMessage ?: "put.io returned HTTP $status",
    )
}

/** The body put.io's API sends for a refused request. */
fun putioErrorBody(status: Int, message: String): String =
    buildJsonObject {
        put("error_id", null as String?)
        put("error_message", message)
        put("error_type", "BadRequest")
        put("error_uri", "http://api.put.io/v2/docs")
        put("status", "ERROR")
        put("status_code", status)
    }.toString()

private val lenientJson = Json { ignoreUnknownKeys = true }
