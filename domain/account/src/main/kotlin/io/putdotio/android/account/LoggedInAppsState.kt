package io.putdotio.android.account

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult

public sealed interface LoggedInAppsContent {
    public data object Loading : LoggedInAppsContent

    public data class Ready(val apps: List<LoggedInApp>) : LoggedInAppsContent

    public data class Failed(val failure: PutioFailure) : LoggedInAppsContent
}

public sealed interface LoggedInAppsWork {
    public data class Revoking(val app: LoggedInApp) : LoggedInAppsWork

    public data class SigningOutOthers(val app: LoggedInApp, val left: Int) : LoggedInAppsWork
}

public sealed interface LoggedInAppsOutcome {
    public data object SignedOutOthers : LoggedInAppsOutcome

    public data class RevokeFailed(val app: LoggedInApp, val failure: PutioFailure) : LoggedInAppsOutcome

    public data class SignOutOthersFailed(val app: LoggedInApp, val failure: PutioFailure) : LoggedInAppsOutcome
}

public data class LoggedInAppsState(
    val content: LoggedInAppsContent = LoggedInAppsContent.Loading,
    val work: LoggedInAppsWork? = null,
    val outcome: LoggedInAppsOutcome? = null,
    val refreshing: Boolean = false,
) {
    private val apps: List<LoggedInApp>?
        get() = (content as? LoggedInAppsContent.Ready)?.apps

    private val thisDevice: LoggedInApp?
        get() = apps.orEmpty().filter(LoggedInApp::thisDevice).singleOrNull()

    val canRevoke: Boolean
        get() = thisDevice != null && work == null && !refreshing && authenticationFailure == null

    val otherApps: List<LoggedInApp>
        get() = apps.orEmpty().filterNot(LoggedInApp::thisDevice)

    internal val signOutOthersSteps: List<LoggedInAppsStep>
        get() = thisDevice?.let { device ->
            otherApps.map(::LoggedInAppsStep) + device.otherSessions.map { LoggedInAppsStep(device, it) }
        }.orEmpty()

    val othersSignedIn: Boolean
        get() = otherApps.isNotEmpty() || thisDevice?.otherSessions.orEmpty().isNotEmpty()

    val canSignOutOthers: Boolean
        get() = canRevoke && signOutOthersSteps.isNotEmpty()

    val authenticationFailure: PutioFailure?
        get() = listOfNotNull(
            (content as? LoggedInAppsContent.Failed)?.failure,
            (outcome as? LoggedInAppsOutcome.RevokeFailed)?.failure,
            (outcome as? LoggedInAppsOutcome.SignOutOthersFailed)?.failure,
        ).firstOrNull { it is PutioFailure.AuthenticationRequired }
}

public sealed interface LoggedInAppsEvent {
    public data object Load : LoggedInAppsEvent

    public data class Revoke(val id: LoggedInAppId) : LoggedInAppsEvent

    public data object SignOutOthers : LoggedInAppsEvent

    public data object DismissOutcome : LoggedInAppsEvent
}

internal data class LoggedInAppsStep(val app: LoggedInApp, val session: LoggedInSessionId? = null)

internal sealed interface LoggedInAppsRequest {
    val id: Long

    data class Load(override val id: Long, val refresh: Boolean = false) : LoggedInAppsRequest

    data class Revoke(
        override val id: Long,
        val step: LoggedInAppsStep,
        val next: List<LoggedInAppsStep>? = null,
    ) : LoggedInAppsRequest {
        val signingOutOthers: Boolean get() = next != null
    }
}

internal data class LoggedInAppsMachine(
    val state: LoggedInAppsState = LoggedInAppsState(),
    val request: LoggedInAppsRequest? = null,
    val nextRequestId: Long = 1L,
) {
    fun transition(event: LoggedInAppsEvent): LoggedInAppsMachine? {
        if (state.authenticationFailure != null) return null
        return when (event) {
            LoggedInAppsEvent.Load -> load()
            is LoggedInAppsEvent.Revoke -> revoke(event.id)
            LoggedInAppsEvent.SignOutOthers -> signOutOthers()
            LoggedInAppsEvent.DismissOutcome ->
                if (state.outcome == null) null else copy(state = state.copy(outcome = null))
        }
    }

    private fun load(): LoggedInAppsMachine? =
        if (request != null) null else copy(
            state = state.copy(content = LoggedInAppsContent.Loading, outcome = null),
            request = LoggedInAppsRequest.Load(nextRequestId),
            nextRequestId = nextRequestId + 1L,
        )

    private fun revoke(id: LoggedInAppId): LoggedInAppsMachine? {
        val app = state.otherApps.firstOrNull { it.id == id }
        return if (request != null || !state.canRevoke || app == null) null else copy(
            state = state.copy(work = LoggedInAppsWork.Revoking(app), outcome = null),
            request = LoggedInAppsRequest.Revoke(nextRequestId, LoggedInAppsStep(app)),
            nextRequestId = nextRequestId + 1L,
        )
    }

    private fun signOutOthers(): LoggedInAppsMachine? {
        if (request != null || !state.canSignOutOthers) return null
        return revokeNext(state.copy(outcome = null), state.signOutOthersSteps)
    }

    private fun revokeNext(from: LoggedInAppsState, steps: List<LoggedInAppsStep>): LoggedInAppsMachine =
        copy(
            state = from.copy(work = LoggedInAppsWork.SigningOutOthers(steps.first().app, left = steps.size)),
            request = LoggedInAppsRequest.Revoke(nextRequestId, steps.first(), steps.drop(1)),
            nextRequestId = nextRequestId + 1L,
        )

    fun completeLoad(request: LoggedInAppsRequest.Load, result: PutioResult<List<LoggedInApp>>): LoggedInAppsMachine =
        copy(
            state = state.copy(
                content = when (result) {
                    is PutioResult.Success -> LoggedInAppsContent.Ready(result.value)
                    is PutioResult.Failure ->
                        if (request.refresh && result.failure !is PutioFailure.AuthenticationRequired) {
                            state.content
                        } else {
                            LoggedInAppsContent.Failed(result.failure)
                        }
                },
                refreshing = false,
            ),
            request = null,
        )

    fun completeRevoke(request: LoggedInAppsRequest.Revoke, result: PutioResult<Unit>): LoggedInAppsMachine {
        if (result is PutioResult.Failure) return revokeFailed(request, result.failure)
        val remaining = state.copy(content = state.content.without(request.step))
        val next = request.next
        return when {
            next == null -> copy(state = remaining.copy(work = null), request = null)
            next.isEmpty() -> copy(
                state = remaining.copy(work = null, outcome = LoggedInAppsOutcome.SignedOutOthers),
                request = null,
            )
            else -> revokeNext(remaining, next)
        }
    }

    private fun revokeFailed(request: LoggedInAppsRequest.Revoke, failure: PutioFailure): LoggedInAppsMachine {
        val outcome = if (request.signingOutOthers) {
            LoggedInAppsOutcome.SignOutOthersFailed(request.step.app, failure)
        } else {
            LoggedInAppsOutcome.RevokeFailed(request.step.app, failure)
        }
        return if (failure is PutioFailure.AuthenticationRequired) {
            copy(state = state.copy(work = null, outcome = outcome), request = null)
        } else {
            copy(
                state = state.copy(work = null, outcome = outcome, refreshing = true),
                request = LoggedInAppsRequest.Load(nextRequestId, refresh = true),
                nextRequestId = nextRequestId + 1L,
            )
        }
    }
}

private fun LoggedInAppsContent.without(step: LoggedInAppsStep): LoggedInAppsContent {
    if (this !is LoggedInAppsContent.Ready) return this
    val session = step.session ?: return LoggedInAppsContent.Ready(apps.filterNot { it.id == step.app.id })
    return LoggedInAppsContent.Ready(
        apps.map { app -> if (app.id == step.app.id) app.copy(otherSessions = app.otherSessions - session) else app },
    )
}
