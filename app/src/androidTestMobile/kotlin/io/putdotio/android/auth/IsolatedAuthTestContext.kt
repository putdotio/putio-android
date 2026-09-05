package io.putdotio.android.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.UUID

// Exercise the production stores and Android Keystore without touching a reusable device's session.
internal class IsolatedAuthTestContext(base: Context) : ContextWrapper(base) {
    private val namespace = "${base.packageName}.auth_test.${UUID.randomUUID()}"

    override fun getPackageName(): String = namespace

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("$namespace.$name", mode)

    fun deleteAuthPreferences() {
        check(baseContext.deleteSharedPreferences("$namespace.$AUTH_PREFERENCES_NAME"))
    }
}
