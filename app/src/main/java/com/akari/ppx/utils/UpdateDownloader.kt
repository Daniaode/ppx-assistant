package com.akari.ppx.utils

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新包下载：先走 GitHub 直链，失败后依次尝试加速代理。
 *
 * 代理只是把原始地址拼在后面，例如：
 *   https://ghfast.top/https://github.com/xxx/app.apk
 */
object UpdateDownloader {

    /** 备用加速代理前缀，按可用性排序。 */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghproxy.cc/"
    )

    private const val UA = "Piyou-Assistant"

    fun mirrorsFor(rawUrl: String): List<String> =
        listOf(rawUrl) + MIRRORS.map { it + rawUrl }

    /**
     * 下载到 cacheDir/update.apk。
     * @param onProgress (已下载字节, 总字节) 总字节为 -1 表示未知
     * @return 下载成功的文件；全部失败返回 null
     */
    fun download(
        context: Context,
        rawUrl: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? {
        val target = File(context.cacheDir, "update.apk")
        for (url in mirrorsFor(rawUrl)) {
            if (tryDownload(url, target, onProgress)) return target
            target.delete()
        }
        return null
    }

    private fun tryDownload(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            if (connection.responseCode != 200) return false
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            // 简单的完整性检查：至少 1MB 才算有效 APK
            target.length() > 1_000_000
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
