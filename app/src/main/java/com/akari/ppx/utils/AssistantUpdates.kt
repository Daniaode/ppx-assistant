package com.akari.ppx.utils

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

data class AssistantRelease(val version: String, val notes: String, val url: String)

object AssistantUpdates {
    // LSPosed 官方模块仓库的 Release 是更新来源。
    private const val API = "https://api.github.com/repos/Daniaode/ppx-assistant/releases/latest"
    const val RELEASES = "https://github.com/Daniaode/ppx-assistant/releases"

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<java.math.BigInteger>? {
            val normalized = value.trim().removePrefix("v")
            if (!normalized.matches(Regex("[0-9]+([.][0-9]+)*"))) return null
            return normalized.split('.').map { it.toBigInteger() }
        }
        val next = parts(candidate) ?: return false
        val installed = parts(current) ?: return false
        for (index in 0 until maxOf(next.size, installed.size)) {
            val comparison = (next.getOrNull(index) ?: java.math.BigInteger.ZERO)
                .compareTo(installed.getOrNull(index) ?: java.math.BigInteger.ZERO)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    /** 仓库标签形如 `20261010-0.0.7`、`v0.0.7` 或 `0.0.7`；发布名优先于标签。 */
    fun releaseVersion(tag: String, name: String?): String? {
        fun plain(value: String): String? =
            value.trim().removePrefix("v").takeIf { it.matches(Regex("[0-9]+([.][0-9]+)*")) }
        plain(name.orEmpty())?.let { return it }
        val normalized = tag.trim().removePrefix("v")
        val candidate = if ('-' in normalized) normalized.substringAfterLast('-') else normalized
        return plain(candidate)
    }

    fun latest(): AssistantRelease? {
        val connection = URL(API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Piyou-Assistant")
            if (connection.responseCode == 404) return null
            kotlin.check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val json = connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) return null
            val tag = json.get("tag_name").asString
            val name = json.get("name")?.takeUnless { it.isJsonNull }?.asString
            val version = releaseVersion(tag, name) ?: tag
            val body = json.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            return AssistantRelease(version, body.ifBlank { "此版本暂无更新说明。" },
                "$RELEASES/tag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
        } finally {
            connection.disconnect()
        }
    }
}
