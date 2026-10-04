package io.putdotio.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The launcher's static shortcuts, read with the platform's own intent parser, as the shortcut service does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileShortcutsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun eachShortcutOpensItsProductLinkInThisVariantsMainActivity() {
        val shortcuts = shortcutIntents()

        assertEquals(listOf("search", "add-transfer", "downloads"), shortcuts.keys.toList())
        for (intent in shortcuts.values) {
            assertEquals(Intent.ACTION_VIEW, intent.action)
            // Explicit: another installed channel answering putio:// never receives it.
            assertEquals(ComponentName(context.packageName, MainActivity::class.java.name), intent.component)
        }
        assertEquals(MobileDeepLink.Search, parseMobileDeepLink(shortcuts.getValue("search").data))
        assertEquals(MobileDeepLink.AddTransfer, parseMobileDeepLink(shortcuts.getValue("add-transfer").data))
        assertEquals(MobileDeepLink.Downloads(), parseMobileDeepLink(shortcuts.getValue("downloads").data))
    }

    @Test
    fun theLauncherActivityPublishesTheShortcuts() {
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        assertEquals(R.xml.shortcuts, activity.metaData.getInt("android.app.shortcuts"))
    }

    @Test
    fun aShortcutOpenedSignedOutWaitsForSignInAsItsLink() {
        for ((id, intent) in shortcutIntents()) {
            val link = parseMobileDeepLink(intent.data)
            Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { controller ->
                val activity = controller.get()
                // MainActivity holds the link until the signed-in shell routes it; nothing else runs.
                assertEquals(id, link, activity.deepLinkRequests.pending.value)
                assertNull(activity.intent.data)
            }
        }
    }

    /** Shortcut id to the intent the platform builds from `res/xml/shortcuts.xml`. */
    private fun shortcutIntents(): Map<String, Intent> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val attributes = Xml.asAttributeSet(parser)
        val intents = linkedMapOf<String, Intent>()
        var shortcutId: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "shortcut" -> shortcutId = parser.getAttributeValue(ANDROID, "shortcutId")
                "intent" -> {
                    // The platform takes the package as written; a resource reference would stay unresolved.
                    assertEquals(context.packageName, parser.getAttributeValue(ANDROID, "targetPackage"))
                    intents[requireNotNull(shortcutId)] = Intent.parseIntent(context.resources, parser, attributes)
                }
            }
        }
        return intents
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
