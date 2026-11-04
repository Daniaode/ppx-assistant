@file:Suppress("unchecked_cast")

package com.akari.ppx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.akari.ppx.App
import com.akari.ppx.data.Const.PREFS_NAME
import com.akari.ppx.data.Const.SETTINGS_REVISION
import com.akari.ppx.utils.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

object Prefs {
    private val Context.dataStore by preferencesDataStore(PREFS_NAME)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore
        get() = App.context.dataStore
    val dsData by lazy {
        runBlocking(scope.coroutineContext) { dataStore.data.stateIn(scope) }
    }

    inline fun <reified T> get(key: String, defaultValue: T? = null): T? =
        runBlocking(scope.coroutineContext) {
            dataStore.data.first()[getPrefsKey<T>(key)] ?: defaultValue
        }

    private val syncMutex = Mutex()

    inline fun <reified T> set(key: String, value: T) {
        // Start in invocation order, including rapid repeated changes to the same key.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { save(getPrefsKey(key), value) }
    }

    inline fun <reified T> setBlocking(key: String, value: T) {
        runBlocking(scope.coroutineContext) { save(getPrefsKey(key), value) }
    }

    @PublishedApi
    internal suspend fun <T> save(key: Preferences.Key<T>, value: T) {
        syncMutex.withLock {
            val snapshot = dataStore.edit { prefs ->
                if (prefs[key] != value) {
                    prefs[key] = value
                    prefs[stringPreferencesKey(SETTINGS_REVISION)] = UUID.randomUUID().toString()
                }
            }
            // Publish the value and revision together. A failed remote service must not
            // prevent the legacy mirror from receiving the saved configuration.
            runCatching { ModuleSharedPrefs.syncAll(snapshot) }.onFailure(Log::e)
            publishRemote(FrameworkScopeState.remotePreferences(), snapshot)
        }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val snapshot = dataStore.data.first()
            ModuleSharedPrefs.syncAll(snapshot)
            publishRemote(FrameworkScopeState.remotePreferences(), snapshot)
        }
    }

    fun syncMirror() {
        runBlocking(scope.coroutineContext) {
            syncMutex.withLock { ModuleSharedPrefs.syncAll(dataStore.data.first()) }
        }
    }

    fun syncRemote(sharedPreferences: SharedPreferences? = FrameworkScopeState.remotePreferences()) {
        if (sharedPreferences == null) return
        runBlocking(scope.coroutineContext) {
            syncMutex.withLock { publishRemote(sharedPreferences, dataStore.data.first()) }
        }
    }

    private fun publishRemote(remote: SharedPreferences?, snapshot: Preferences) {
        if (remote == null) return
        runCatching {
            val editor = remote.edit().clear()
            snapshot.asMap().forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key.name, value)
                    is Boolean -> editor.putBoolean(key.name, value)
                    is Int -> editor.putInt(key.name, value)
                    is Long -> editor.putLong(key.name, value)
                    is Float -> editor.putFloat(key.name, value)
                    is Set<*> -> editor.putStringSet(key.name, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Remote settings synchronization failed" }
        }.onFailure(Log::e)
    }

    inline fun <reified T> getPrefsKey(key: String): Preferences.Key<T> =
        when (T::class.java) {
            Boolean::class.java -> booleanPreferencesKey(key)
            else -> stringPreferencesKey(key)
        } as Preferences.Key<T>
}
