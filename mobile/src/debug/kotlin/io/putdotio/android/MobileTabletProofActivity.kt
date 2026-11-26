package io.putdotio.android

import android.view.KeyboardShortcutGroup
import android.view.Menu

class MobileTabletProofActivity : BasePutioActivity() {
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += mobileKeyboardShortcutGroups(this)
    }
}
