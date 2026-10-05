package io.putdotio.android

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat

/** What a hardware key asks for once no focused control used it. */
internal enum class MobileKeyCommand { Back, Search, Refresh, Delete, Open, PlayPause, SeekBack, SeekForward }

internal enum class MobileShortcutScope(@StringRes val labelRes: Int) {
    Files(R.string.mobile_shortcuts_files),
    Player(R.string.mobile_shortcuts_player),
}

internal class MobileKeyboardShortcut(
    val scope: MobileShortcutScope,
    @StringRes val labelRes: Int,
    val keyCode: Int,
    val ctrl: Boolean,
    val command: MobileKeyCommand,
)

/**
 * The shortcuts the app takes, which also fill the system Keyboard Shortcuts Helper. Android's own
 * shortcuts use Meta or Alt, so these take only bare keys and Ctrl. Enter is listed but belongs to the
 * focused row, which opens on it like a tap.
 */
internal val MobileKeyboardShortcuts: List<MobileKeyboardShortcut> = listOf(
    files(R.string.mobile_shortcut_open, KeyEvent.KEYCODE_ENTER, MobileKeyCommand.Open),
    files(R.string.mobile_shortcut_back, KeyEvent.KEYCODE_DEL, MobileKeyCommand.Back),
    files(R.string.mobile_shortcut_back, KeyEvent.KEYCODE_ESCAPE, MobileKeyCommand.Back),
    files(R.string.mobile_shortcut_search, KeyEvent.KEYCODE_F, MobileKeyCommand.Search, ctrl = true),
    files(R.string.mobile_shortcut_refresh, KeyEvent.KEYCODE_R, MobileKeyCommand.Refresh, ctrl = true),
    files(R.string.mobile_shortcut_refresh, KeyEvent.KEYCODE_F5, MobileKeyCommand.Refresh),
    files(R.string.mobile_shortcut_delete, KeyEvent.KEYCODE_FORWARD_DEL, MobileKeyCommand.Delete),
    player(R.string.mobile_shortcut_play_pause, KeyEvent.KEYCODE_SPACE, MobileKeyCommand.PlayPause),
    player(R.string.mobile_shortcut_seek_back, KeyEvent.KEYCODE_DPAD_LEFT, MobileKeyCommand.SeekBack),
    player(R.string.mobile_shortcut_seek_forward, KeyEvent.KEYCODE_DPAD_RIGHT, MobileKeyCommand.SeekForward),
    player(R.string.mobile_shortcut_back, KeyEvent.KEYCODE_ESCAPE, MobileKeyCommand.Back),
    player(R.string.mobile_shortcut_back, KeyEvent.KEYCODE_DEL, MobileKeyCommand.Back),
)

private fun files(@StringRes label: Int, keyCode: Int, command: MobileKeyCommand, ctrl: Boolean = false) =
    MobileKeyboardShortcut(MobileShortcutScope.Files, label, keyCode, ctrl, command)

private fun player(@StringRes label: Int, keyCode: Int, command: MobileKeyCommand) =
    MobileKeyboardShortcut(MobileShortcutScope.Player, label, keyCode, ctrl = false, command)

/** The command [keyCode] with [metaState] gives in [scope]; Shift, Alt, Meta and any other combination give none. */
internal fun mobileKeyCommand(scope: MobileShortcutScope, keyCode: Int, metaState: Int): MobileKeyCommand? {
    val ctrl = when {
        KeyEvent.metaStateHasNoModifiers(metaState) -> false
        KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_CTRL_ON) -> true
        else -> return null
    }
    return MobileKeyboardShortcuts.firstOrNull {
        it.scope == scope && it.keyCode == keyCode && it.ctrl == ctrl && it.command != MobileKeyCommand.Open
    }?.command
}

internal fun mobileKeyboardShortcutGroups(context: Context): List<KeyboardShortcutGroup> =
    MobileShortcutScope.entries.map { scope ->
        KeyboardShortcutGroup(
            context.getString(scope.labelRes),
            MobileKeyboardShortcuts.filter { it.scope == scope }.map {
                KeyboardShortcutInfo(
                    context.getString(it.labelRes),
                    it.keyCode,
                    if (it.ctrl) KeyEvent.META_CTRL_ON else 0,
                )
            },
        )
    }

/**
 * Runs [onCommand] for keys of [scope] that reached the window unused, so a text field keeps Backspace
 * and a focused button keeps Enter. Returning true consumes the key, Escape included, which Android
 * would otherwise turn into Back and leave the app from its top screen. A held key repeats only when
 * [repeats] says so.
 */
@Composable
internal fun MobileUnhandledKeyEffect(
    scope: MobileShortcutScope,
    enabled: Boolean = true,
    repeats: (MobileKeyCommand) -> Boolean = { false },
    onCommand: (MobileKeyCommand) -> Boolean,
) {
    val view = LocalView.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRepeats by rememberUpdatedState(repeats)
    val currentOnCommand by rememberUpdatedState(onCommand)
    DisposableEffect(view, scope) {
        val listener = ViewCompat.OnUnhandledKeyEventListenerCompat { _: View, event: KeyEvent ->
            val command = mobileKeyCommand(scope, event.keyCode, event.metaState)
            when {
                !currentEnabled || command == null || event.action != KeyEvent.ACTION_DOWN -> false
                event.repeatCount > 0 && !currentRepeats(command) -> true
                else -> currentOnCommand(command)
            }
        }
        ViewCompat.addOnUnhandledKeyEventListener(view, listener)
        onDispose { ViewCompat.removeOnUnhandledKeyEventListener(view, listener) }
    }
}
