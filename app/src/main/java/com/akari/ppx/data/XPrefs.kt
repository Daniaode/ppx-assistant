package com.akari.ppx.data

import android.content.SharedPreferences
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.data.Const.PREFS_NAME
import com.akari.ppx.utils.HookRuntime
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.checkUnless
import de.robv.android.xposed.XSharedPreferences

object XPrefs {
    @PublishedApi
    internal val sharedPrefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        XSharedPreferences(APPLICATION_ID, PREFS_NAME)
    }

    @PublishedApi
    internal fun prefs(): XSharedPreferences = sharedPrefs.also {
        runCatching { it.reload() }.onFailure { error ->
            Log.e("XPrefs reload failed")
            Log.e(error)
        }
        runCatching {
            Log.i("XPrefs file=${it.file} canRead=${it.file?.canRead()}")
        }.onFailure(Log::e)
    }

    @PublishedApi
    internal fun remotePrefs(): SharedPreferences? = HookRuntime.remotePreferences()?.also {
        Log.i("XPrefs remote preferences available")
    }

    @PublishedApi
    internal fun read(key: String, default: Any): Any {
        val remote = remotePrefs()
        if (remote != null) {
            runCatching {
                if (remote.contains(key)) return value(remote, key, default)
            }.onFailure(Log::e)
        }
        // Some frameworks provide an empty remote store before service binding.
        // Do not let its default values hide an existing legacy configuration.
        return runCatching { value(prefs(), key, default) }
            .onFailure(Log::e).getOrDefault(default)
    }

    private fun value(prefs: SharedPreferences, key: String, default: Any): Any = when (default) {
        is Boolean -> prefs.getBoolean(key, default)
        is String -> prefs.getString(key, default) ?: default
        else -> default
    }

    inline operator fun <reified T> invoke(key: String, defValue: T? = null): T =
        when (T::class.java) {
            String::class.java -> read(key, (defValue ?: "") as String)
            java.lang.Boolean::class.java -> if (!AutomationAvailability.isSettingAvailable(key)) false
                else read(key, (defValue ?: false) as Boolean)
            else -> null
        } as T

    inline fun checkUnless(key: String, unsatisfiedAction: () -> Unit) =
        XPrefs<Boolean>(key).checkUnless(true) { unsatisfiedAction() }
}
