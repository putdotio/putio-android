package io.putdotio.android.auth

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.putdotio.android.design.PutioTheme
import io.putdotio.sdk.auth.DeviceCodeAuthState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug builds only: signs the app in for harnesses, with no browser. A token the harness pushed to
 * [HARNESS_IMPORT_TOKEN] is used first; otherwise the screen shows a device code the `putio` CLI
 * approves, and with [EXTRA_EXPORT_TOKEN] the new token lands in [HARNESS_EXPORT_TOKEN] for the
 * harness to cache. Either token goes through [MobileAuthController.completeDeviceLink], so the
 * session is the one a browser sign-in stores. The screen shows the code, then the signed-in
 * account's ID, as plain text nodes.
 */
class DebugDeviceSignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val signIn = ViewModelProvider(this)[DebugDeviceSignIn::class.java]
        setContent {
            PutioTheme {
                val state by signIn.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    Text(state.label(), Modifier.safeDrawingPadding().padding(16.dp))
                }
            }
        }
    }
}

internal sealed interface DebugDeviceSignInState {
    data object Starting : DebugDeviceSignInState

    data class AwaitingApproval(
        val code: String,
    ) : DebugDeviceSignInState

    data object Validating : DebugDeviceSignInState

    data class SignedIn(
        val userId: Long,
    ) : DebugDeviceSignInState

    data class Stopped(
        val reason: String,
    ) : DebugDeviceSignInState

    data class ImportRejected(
        val reason: String,
    ) : DebugDeviceSignInState
}

/** Relative to the app's data directory, which is where `adb shell run-as` starts. */
internal const val HARNESS_IMPORT_TOKEN = "files/harness/import-token"
internal const val HARNESS_EXPORT_TOKEN = "files/harness/export-token"
internal const val EXTRA_EXPORT_TOKEN = "io.putdotio.android.harness.EXPORT_TOKEN"


// The harness reads these with uiautomator; scripts/sign-in-devs-auto.sh matches them.
private fun DebugDeviceSignInState.label(): String =
    when (this) {
        DebugDeviceSignInState.Starting -> "Device sign-in: starting"
        is DebugDeviceSignInState.AwaitingApproval -> "Activation code $code"
        DebugDeviceSignInState.Validating -> "Device sign-in: validating"
        is DebugDeviceSignInState.SignedIn -> "Signed in account $userId"
        is DebugDeviceSignInState.Stopped -> "Device sign-in stopped: $reason"
        is DebugDeviceSignInState.ImportRejected -> "Cached token rejected: $reason"
    }

/** One attempt per activity, kept across configuration changes so a rotation does not replace the code. */
internal class DebugDeviceSignIn(
    application: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val runtime = MobileOAuthRuntime.get(application)
    private val dataDir = application.dataDir
    private val exportsToken = savedState.get<Boolean>(EXTRA_EXPORT_TOKEN) == true
    private val mutableState = MutableStateFlow<DebugDeviceSignInState>(DebugDeviceSignInState.Starting)

    val state: StateFlow<DebugDeviceSignInState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { signIn() }
    }

    private suspend fun signIn() {
        // Consumed before anything else, so a pushed token never outlives this launch.
        val imported = takeImportedToken()
        val restored = restoredState()
        when {
            restored != null -> mutableState.value = restored
            imported != null -> mutableState.value = withContext(NonCancellable) { completeImport(imported) }
            else -> linkWithCode()
        }
    }

    /** The settled state when the app is not signed out, else null. */
    private suspend fun restoredState(): DebugDeviceSignInState? {
        val controller = runtime.authController
        controller.restoreSession()
        return when (val settled = controller.state.first { it.isSettled() }) {
            // A session that opened offline is not proof that put.io accepts it.
            is MobileAuthState.SignedIn ->
                if (settled.validated) {
                    DebugDeviceSignInState.SignedIn(settled.account.userId)
                } else {
                    DebugDeviceSignInState.Stopped("app is signed in offline; put.io has not confirmed the session")
                }
            is MobileAuthState.SignedOut -> null
            else -> DebugDeviceSignInState.Stopped("app is ${settled::class.simpleName}")
        }
    }

    private suspend fun linkWithCode() {
        runtime.putioClient.deviceCodeAuth.link().collect { link ->
            when (link) {
                DeviceCodeAuthState.Requesting -> Unit
                is DeviceCodeAuthState.AwaitingLink ->
                    mutableState.value = DebugDeviceSignInState.AwaitingApproval(link.code)
                DeviceCodeAuthState.Validating -> mutableState.value = DebugDeviceSignInState.Validating
                // The token leaves the SDK here, once; leaving the screen must not drop it half stored.
                is DeviceCodeAuthState.Linked ->
                    mutableState.value = withContext(NonCancellable) { complete(link) }
                is DeviceCodeAuthState.Expired ->
                    mutableState.value = DebugDeviceSignInState.Stopped("code expired (${link.reason})")
                is DeviceCodeAuthState.Failed ->
                    mutableState.value = DebugDeviceSignInState.Stopped(link.error::class.simpleName ?: "put.io error")
            }
        }
    }

    private suspend fun complete(link: DeviceCodeAuthState.Linked): DebugDeviceSignInState {
        val accessToken = AccessToken.parse(link.accessToken)
            ?: return DebugDeviceSignInState.Stopped("put.io returned an unusable token")
        val result = completeWith(accessToken)
        if (exportsToken && result is DebugDeviceSignInState.SignedIn) {
            File(dataDir, HARNESS_EXPORT_TOKEN).apply { parentFile?.mkdirs() }.writeText(link.accessToken)
        }
        return result
    }

    private suspend fun completeImport(token: String): DebugDeviceSignInState {
        val accessToken = AccessToken.parse(token)
            ?: return DebugDeviceSignInState.ImportRejected("not a put.io token")
        val result = completeWith(accessToken)
        val after = runtime.authController.state.value
        return if (after is MobileAuthState.SignedOut && after.reason == MobileSignedOutReason.SessionExpired) {
            DebugDeviceSignInState.ImportRejected("put.io rejected it")
        } else {
            result
        }
    }

    private suspend fun completeWith(accessToken: AccessToken): DebugDeviceSignInState {
        val controller = runtime.authController
        return when {
            !controller.completeDeviceLink(accessToken) ->
                DebugDeviceSignInState.Stopped("the app is not signed out, or a browser sign-in is pending")
            else -> when (val after = controller.state.value) {
                is MobileAuthState.SignedIn -> DebugDeviceSignInState.SignedIn(after.account.userId)
                is MobileAuthState.SignedOut -> DebugDeviceSignInState.Stopped("signed out (${after.reason})")
                else -> DebugDeviceSignInState.Stopped("app is ${after::class.simpleName}")
            }
        }
    }

    private fun takeImportedToken(): String? {
        val file = File(dataDir, HARNESS_IMPORT_TOKEN)
        if (!file.exists()) return null
        return try {
            file.readText().trim().takeIf { it.isNotEmpty() }
        } finally {
            file.delete()
        }
    }
}
