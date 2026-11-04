@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.getObjectFieldOrNull
import com.akari.ppx.utils.replaceMethod
import com.akari.ppx.utils.setObjectField
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook
import java.util.ArrayList

class ImageHook : SwitchHook("save_image") {
    override fun onHook() {
        "com.sup.android.m_gallery.NewGalleryActivity".replaceMethod(cl, "onDownload") { param ->
            val activity = param.thisObject
            val images = activity.callMethodOrNullAs<List<*>>("getImages")
                ?: return@replaceMethod param.invokeOriginalMethod()
            val index = activity.callMethodOrNull("getVpGallery")
                ?.callMethodOrNullAs<Int>("getCurrentItem")
                ?: return@replaceMethod param.invokeOriginalMethod()
            val current = images.getOrNull(index)
                ?: return@replaceMethod param.invokeOriginalMethod()
            val displayList = current.callMethodOrNullAs<List<*>>("getUrlList")
                ?.takeIf { it.isNotEmpty() }
                ?: return@replaceMethod param.invokeOriginalMethod()
            val isGif = current.callMethodOrNullAs<Boolean>("isGif") == true
            val displayUrl = displayList.firstOrNull()
                ?.callMethodOrNullAs<String>("getUrl")
                ?.takeIf { it.isNotBlank() }
                ?: return@replaceMethod param.invokeOriginalMethod()
            val originalDownloadList = current.getObjectFieldOrNull("downloadList")
            val originalDownloadUrl = (originalDownloadList as? List<*>)?.firstOrNull()
                ?.callMethodOrNullAs<String>("getUrl")
            val shouldSwap = shouldSwapDownloadSource(isGif, displayUrl, originalDownloadUrl)
            Log.i(
                "ImageHook choose source index=$index isGif=$isGif swap=$shouldSwap " +
                    "download=$originalDownloadUrl display=$displayUrl"
            )
            if (!shouldSwap) {
                return@replaceMethod param.invokeOriginalMethod()
            }
            try {
                current.setObjectField("downloadList", ArrayList(displayList))
                param.invokeOriginalMethod()
            } finally {
                runCatching {
                    current.setObjectField("downloadList", originalDownloadList)
                }.onFailure(Log::e)
            }
        }
    }

    private fun shouldSwapDownloadSource(
        isGif: Boolean,
        displayUrl: String,
        originalDownloadUrl: String?
    ): Boolean {
        if (!isGif) {
            return true
        }
        if (looksLikeAnimatedSource(displayUrl)) {
            return true
        }
        Log.i(
            "ImageHook keep original gif source " +
                "download=$originalDownloadUrl display=$displayUrl"
        )
        return false
    }

    private fun looksLikeAnimatedSource(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".gif") || lower.contains("format=gif") || lower.contains("image/gif")
    }
}
