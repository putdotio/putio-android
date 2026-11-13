package io.putdotio.android.tv

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvExportedComponentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageManager = shadowOf(context.packageManager)

    @Test
    fun `other apps reach only the reviewed components and intent filters`() {
        assertEquals(emptyList<String>(), exported().filterNot { it in REVIEWED })
    }

    @Test
    fun `what another app sends through the main activity's filters, without the install's key, opens nothing`() {
        packageManager.getIntentFiltersForActivity(ComponentName(context, MainActivity::class.java))
            .flatMap { filter -> filter.actionsIterator().asSequence().map { filter.senderIntent(it) }.toList() }
            .forEach { intent ->
                assertNull(intent.toString(), intent.consumeTvLaunchRequest { TvLinkKey.get(context) })
            }
    }

    private fun IntentFilter.senderIntent(action: String): Intent {
        val intent = Intent(action).putExtra(SearchManager.QUERY, "harbor")
        val scheme = schemesIterator()?.asSequence()?.singleOrNull() ?: return intent
        val host = checkNotNull(authoritiesIterator()?.asSequence()?.singleOrNull()?.host) {
            "Give this test a link for the $scheme filter"
        }
        return intent.setData("$scheme://$host/55".toUri())
    }

    private fun exported(): List<String> {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )
        val pm = packageManager
        return listOf(
            info.activities.orEmpty().map { describe("activity", it, it.permission, pm::getIntentFiltersForActivity) },
            info.services.orEmpty().map { describe("service", it, it.permission, pm::getIntentFiltersForService) },
            info.receivers.orEmpty().map { describe("receiver", it, it.permission, pm::getIntentFiltersForReceiver) },
            info.providers.orEmpty().map {
                describe("provider", it, it.readPermission, pm::getIntentFiltersForProvider)
            },
        ).flatten().filterNotNull().sorted()
    }

    private fun describe(
        kind: String,
        component: ComponentInfo,
        permission: String?,
        filters: (ComponentName) -> List<IntentFilter>,
    ): String? {
        if (!component.exported) return null
        val filterText = filters(ComponentName(component.packageName, component.name))
            .map { it.describe() }
            .sorted()
            .joinToString(" ; ")
        return "$kind ${component.name} permission=$permission filters=[$filterText]"
    }

    private fun IntentFilter.describe(): String =
        listOf(
            actionsIterator()?.asSequence(),
            categoriesIterator()?.asSequence(),
            schemesIterator()?.asSequence(),
            authoritiesIterator()?.asSequence()?.map { it.host },
        ).joinToString("|") { it.orEmpty().sorted().joinToString(",") }

    private companion object {
        val REVIEWED = setOf(
            "activity io.putdotio.android.MainActivity permission=null filters=[" +
                "android.intent.action.MAIN|android.intent.category.LAUNCHER|| ; " +
                "android.intent.action.MAIN|android.intent.category.LEANBACK_LAUNCHER|| ; " +
                "android.intent.action.SEARCH||| ; " +
                "android.intent.action.VIEW|android.intent.category.DEFAULT|putio|files]",
            "provider io.putdotio.android.tv.search.TvSearchSuggestionsProvider " +
                "permission=android.permission.GLOBAL_SEARCH filters=[]",
            "provider io.putdotio.android.tv.TvLauncherArtProvider permission=null filters=[]",
            "activity androidx.media3.session.BluetoothValidationActivity " +
                "permission=android.permission.BLUETOOTH_PRIVILEGED " +
                "filters=[android.intent.action.VIEW||content|media]",
            "receiver androidx.profileinstaller.ProfileInstallReceiver permission=android.permission.DUMP filters=[" +
                "androidx.profileinstaller.action.BENCHMARK_OPERATION||| ; " +
                "androidx.profileinstaller.action.INSTALL_PROFILE||| ; " +
                "androidx.profileinstaller.action.SAVE_PROFILE||| ; " +
                "androidx.profileinstaller.action.SKIP_FILE|||]",
            "activity androidx.activity.ComponentActivity permission=null filters=[]",
            "activity androidx.compose.ui.tooling.PreviewActivity permission=null filters=[]",
        )
    }
}
