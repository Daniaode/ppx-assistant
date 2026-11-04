package com.akari.ppx.utils

import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.HookRuntime
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CustomEmoticon(
    val uri: String,
    val w: Int = 0,
    val h: Int = 0,
    val gif: Boolean = false
)

data class EmoticonGroup(
    val name: String,
    val emojis: MutableList<CustomEmoticon> = mutableListOf()
)

/**
 * Module-side custom emoticon groups. Entries reference the SOURCE uri so the
 * storage survives export/import: insertion re-collects on demand through the
 * host's own EmojiService (which dedupes by uri).
 */
object EmoticonGroups {
    private const val KEY = "custom_emoticon_groups"
    private val gson = Gson()
    private var cache: MutableList<EmoticonGroup>? = null

    @Synchronized
    fun load(): MutableList<EmoticonGroup> {
        cache?.let { return it }
        val json = runCatching { XPrefs<String>(KEY, "") }.getOrDefault("")
        val groups = runCatching {
            gson.fromJson<MutableList<EmoticonGroup>>(
                json.ifBlank { "[]" },
                object : TypeToken<MutableList<EmoticonGroup>>() {}.type
            )
        }.getOrNull() ?: mutableListOf()
        groups.removeAll { it.name == null || it.emojis == null }
        groups.forEach { g -> g.emojis.removeAll { it?.uri.isNullOrBlank() } }
        return groups.also { cache = it }
    }

    @Synchronized
    fun save(groups: List<EmoticonGroup>) {
        cache = groups.toMutableList()
        val json = gson.toJson(groups)
        Thread {
            // The host cannot see the module provider (package visibility), so
            // persist through LSPosed's remote preferences bridge instead.
            runCatching {
                HookRuntime.remotePreferences()?.edit()?.putString(KEY, json)?.apply()
                    ?: throw IllegalStateException("remote preferences unavailable")
            }.onFailure(Log::e)
        }.start()
    }

    @Synchronized
    fun export(): String = gson.toJson(load())

    /** Merges imported groups by name and emoji uri; returns the added count. */
    @Synchronized
    fun import(json: String): Int {
        val imported = runCatching {
            gson.fromJson<List<EmoticonGroup>>(
                json,
                object : TypeToken<List<EmoticonGroup>>() {}.type
            )
        }.getOrNull() ?: return -1
        val groups = load()
        var added = 0
        imported.filterNotNull().forEach { incoming ->
            if (incoming.name.isNullOrBlank()) return@forEach
            val target = groups.firstOrNull { it.name == incoming.name }
                ?: EmoticonGroup(incoming.name, mutableListOf()).also(groups::add)
            incoming.emojis?.filterNotNull()?.forEach { emoji ->
                if (emoji.uri.isNotBlank() && target.emojis.none { it.uri == emoji.uri }) {
                    target.emojis.add(emoji)
                    added++
                }
            }
        }
        save(groups)
        return added
    }

    @Synchronized
    fun addEmoji(groupName: String, emoji: CustomEmoticon): Boolean {
        val groups = load()
        val target = groups.firstOrNull { it.name == groupName }
            ?: EmoticonGroup(groupName, mutableListOf()).also(groups::add)
        if (target.emojis.any { it.uri == emoji.uri }) return false
        target.emojis.add(emoji)
        save(groups)
        return true
    }

    @Synchronized
    fun removeEmoji(groupName: String, uri: String): Boolean {
        val groups = load()
        val target = groups.firstOrNull { it.name == groupName } ?: return false
        val removed = target.emojis.removeAll { it.uri == uri }
        if (target.emojis.isEmpty()) groups.remove(target)
        if (removed) save(groups)
        return removed
    }

    @Synchronized
    fun createGroup(name: String): Boolean {
        if (name.isBlank() || load().any { it.name == name }) return false
        val groups = load()
        groups.add(EmoticonGroup(name, mutableListOf()))
        save(groups)
        return true
    }

    @Synchronized
    fun deleteGroup(name: String): Boolean {
        val groups = load()
        val removed = groups.removeAll { it.name == name }
        if (removed) save(groups)
        return removed
    }

    @Synchronized
    fun renameGroup(old: String, new: String): Boolean {
        if (new.isBlank() || load().any { it.name == new }) return false
        val groups = load()
        val target = groups.firstOrNull { it.name == old } ?: return false
        groups.add(groups.indexOf(target), EmoticonGroup(new, target.emojis))
        groups.remove(target)
        save(groups)
        return true
    }
}
