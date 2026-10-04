package io.putdotio.android

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.transfers.TransferRetryOutcome
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.transfersState
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertSame
import org.junit.Test

class TransfersSessionFailureTest {
    @Test
    fun `a retry rejected for the session expires it`() {
        val failure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state =
            transfersState(
                content = TransfersContent.Empty,
                retryOutcome = TransferRetryOutcome.Failed(TransfersRequestId(2L), failure),
            )

        assertSame(failure, state.authoritativeSessionFailure())
    }
}
