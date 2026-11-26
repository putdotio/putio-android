package io.putdotio.android

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.Window
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

internal fun Activity.pressHardwareKey(keyCode: Int, metaState: Int = 0, repeats: Int = 0) {
    val root = ReflectionHelpers.callInstanceMethod<Any>(window.decorView, "getViewRootImpl")
    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int, repeat: Int) {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, keyCode, repeat, metaState,
            HARDWARE_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            root,
            "dispatchInputEvent",
            ClassParameter.from(InputEvent::class.java, event),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
    send(KeyEvent.ACTION_DOWN, 0)
    repeat(repeats) { send(KeyEvent.ACTION_DOWN, it + 1) }
    send(KeyEvent.ACTION_UP, 0)
}

private const val HARDWARE_KEYBOARD = 1

internal fun Activity.pressHardwareKeyConsumed(keyCode: Int, metaState: Int = 0): Boolean {
    val original = window.callback
    var consumed: Boolean? = null
    window.callback = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            original.dispatchKeyEvent(event).also {
                if (event.keyCode == keyCode && event.action == KeyEvent.ACTION_DOWN) consumed = it
            }
    }
    try {
        pressHardwareKey(keyCode, metaState)
    } finally {
        window.callback = original
    }
    return requireNotNull(consumed) { "The window never saw the key" }
}
