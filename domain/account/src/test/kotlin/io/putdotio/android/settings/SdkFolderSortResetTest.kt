package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkFolderSortResetTest {
    @Test
    fun resetClearsEveryFolderSortWithoutTouchingAccountSettings() = runBlocking {
        val requests = mutableListOf<String>()
        val repository = SdkAccountSettingsRepository(client(requests, 200, OK))

        val result = repository.save(AccountSettingsChange.ResetFolderSorts)

        assertEquals(AccountSettingsRepositoryResult.Success(Unit), result)
        assertEquals(listOf("POST https://api.put.io/v2/files/remove-sort-by-settings"), requests)
    }

    @Test
    fun aRejectedSessionFailsTheResetAsTheSessionVerdict() = runBlocking {
        val result = SdkAccountSettingsRepository(client(mutableListOf(), 401, UNAUTHORIZED))
            .save(AccountSettingsChange.ResetFolderSorts)

        val failure = (result as AccountSettingsRepositoryResult.Failure).failure
        assertTrue(failure.putioFailure is PutioFailure.AuthenticationRequired)
    }

    private fun client(requests: MutableList<String>, status: Int, body: String): PutioClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += "${chain.request().method} ${chain.request().url}"
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return PutioClient(PutioConfig(accessToken = "token"), http)
    }

    private companion object {
        const val OK = """{"status":"OK"}"""
        const val UNAUTHORIZED =
            """{"status":"ERROR","error_type":"invalid_grant","error_message":"Unauthorized","status_code":401}"""
    }
}
