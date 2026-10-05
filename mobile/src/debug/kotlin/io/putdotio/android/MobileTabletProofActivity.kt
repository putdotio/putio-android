package io.putdotio.android

import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity

/**
 * Hosts the tablet proof's production shell over faked repositories. It takes the same configuration
 * changes as MainActivity, so split-screen resizing reaches the shell as it does in the app, and it
 * fills the Keyboard Shortcuts Helper the same way.
 */
class MobileTabletProofActivity : ComponentActivity() {
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += mobileKeyboardShortcutGroups(this)
    }
}
