package io.putdotio.android.account

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoggedInAppsControllerTest {
    private val thisDevice = loggedInApp(1L, "put.io for Android", thisDevice = true)
    private val ios = loggedInApp(2L, "put.io for iOS", Instant.parse("2026-11-09T10:00:00Z"))
    private val appleTv = loggedInApp(3L, "put.io for Apple TV", Instant.parse("2026-11-08T10:00:00Z"))
    private val infuse = loggedInApp(4L, "Infuse", Instant.parse("2026-10-30T10:00:00Z"))

    @Test
    fun thisDeviceCannotRevokeItself() = runTest {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios))
        val controller = loaded(repository)

        assertFalse(controller.dispatch(LoggedInAppsEvent.Revoke(thisDevice.id)))
        runCurrent()

        assertEquals(emptyList<LoggedInAppId>(), repository.revoked)
        assertEquals(listOf(thisDevice, ios), apps(controller))
    }

    @Test
    fun revokeRemovesOnlyThatAppOncePutioConfirms() = runTest {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios, appleTv))
        val controller = loaded(repository)

        assertTrue(controller.dispatch(LoggedInAppsEvent.Revoke(ios.id)))
        assertEquals(LoggedInAppsWork.Revoking(ios), controller.state.value.work)
        assertFalse(controller.dispatch(LoggedInAppsEvent.Revoke(appleTv.id)))
        runCurrent()

        assertEquals(listOf(ios.id), repository.revoked)
        assertEquals(listOf(thisDevice, appleTv), apps(controller))
        assertNull(controller.state.value.work)
        assertNull(controller.state.value.outcome)
    }

    @Test
    fun signingOutOfOthersRevokesEachOtherAppAndKeepsThisDevice() = runTest {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios, appleTv, infuse))
        val controller = loaded(repository)

        assertTrue(controller.dispatch(LoggedInAppsEvent.SignOutOthers))
        runCurrent()

        assertEquals(listOf(ios.id, appleTv.id, infuse.id), repository.revoked)
        assertEquals(listOf(thisDevice), apps(controller))
        assertEquals(LoggedInAppsOutcome.SignedOutOthers, controller.state.value.outcome)
        assertFalse(controller.state.value.canSignOutOthers)
    }

    @Test
    fun signingOutOfOthersStopsAtTheFirstFailureAndReadsTheAppsAgain() = runTest {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios, appleTv, infuse))
        val failure = PutioFailure.NetworkUnavailable(IOException("offline"))
        repository.onRevoke = { id ->
            if (id == appleTv.id) {
                PutioResult.Failure(failure)
            } else {
                repository.apps.removeAll { it.id == id }
                PutioResult.Success(Unit)
            }
        }
        val controller = loaded(repository)

        controller.dispatch(LoggedInAppsEvent.SignOutOthers)
        runCurrent()

        assertEquals(listOf(ios.id, appleTv.id), repository.revoked)
        assertEquals(LoggedInAppsOutcome.SignOutOthersFailed(appleTv, failure), controller.state.value.outcome)
        assertEquals(2, repository.listCount)
        assertEquals(listOf(thisDevice, appleTv, infuse), apps(controller))
        assertTrue(controller.state.value.canSignOutOthers)
    }

    @Test
    fun signingOutOfOthersWaitsUntilThisDeviceIsKnown() = runTest {
        val repository = FakeLoggedInAppsRepository(listOf(ios, appleTv))
        val controller = loaded(repository)

        assertFalse(controller.state.value.canSignOutOthers)
        assertFalse(controller.dispatch(LoggedInAppsEvent.SignOutOthers))
        runCurrent()

        assertEquals(emptyList<LoggedInAppId>(), repository.revoked)
    }

    private fun apps(controller: LoggedInAppsController): List<LoggedInApp> =
        (controller.state.value.content as LoggedInAppsContent.Ready).apps

    private fun TestScope.loaded(repository: LoggedInAppsRepository): LoggedInAppsController {
        val controller = LoggedInAppsController(repository, backgroundScope)
        controller.dispatch(LoggedInAppsEvent.Load)
        runCurrent()
        check(controller.state.value.content is LoggedInAppsContent.Ready)
        return controller
    }
}
