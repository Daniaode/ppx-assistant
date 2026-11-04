@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import android.app.Application
import android.content.Context
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook
import org.json.JSONObject

/**
 * 去除开屏广告：阻断广告 SDK 初始化与开屏广告的加载/展示。
 * 6.6.0 的 AdService 保持可读类名，方法 initComponent/initPangleAdSDK/
 * initMannorSDK 均存在；loadToutiaoSplash 为开屏广告加载入口。
 */
class SplashAdHook : SwitchHook("remove_splash_ad") {
    override fun onHook() {
        runCatching {
            val adService = "com.sup.android.superb.m_ad.AdService".findClass(cl)
            adService.replaceMethod("initComponent") { null }
            adService.replaceMethod("initPangleAdSDK", Application::class.java) { null }
            adService.replaceMethod("initMannorSDK", Application::class.java) { null }
            adService.replaceMethod(
                "loadToutiaoSplash",
                "com.sup.android.superb.i_ad.interfaces.ITouttiaoSplashListener"
            ) { null }
            adService.replaceMethod(
                "startAdSplashActivity\$m_ad_cnRelease",
                Context::class.java
            ) { null }
        }.onFailure { Log.e("SplashAdHook attach failed: $it") }
    }
}

/**
 * 去除广告牛皮癣：在网络层过滤 banner 与热搜广告。
 * 宿主的网络请求统一走 HttpRequest.doGet/doPost，返回 JSON 字符串；
 * 这里按 URL 过滤：
 *  - bds/banner        -> data.banners 置空（我的/搜索/皮友圈等横幅广告）
 *  - bds/search/hot    -> 移除 schema 为 bds://webview 的热搜广告词
 */
class MiscAdHook : SwitchHook("remove_misc_ads") {
    override fun onHook() {
        runCatching {
            val httpRequest = "com.ss.android.socialbase.basenetwork.HttpRequest".findClass(cl)
            listOf("doGet", "doPost").forEach { method ->
                httpRequest.hookAfterMethod(method) { param ->
                    val body = param.result as? String ?: return@hookAfterMethod
                    val url = param.thisObjectOrNull?.getObjectFieldOrNull("url") as? String
                        ?: return@hookAfterMethod
                    param.result = filterBody(url, body)
                }
            }
        }.onFailure { Log.e("MiscAdHook attach failed: $it") }
    }

    private fun filterBody(url: String, body: String): String {
        if (url.contains("bds/banner")) {
            return runCatching {
                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                if (data != null) {
                    data.put("banners", JSONObject.NULL)
                    json.put("data", data)
                }
                json.toString()
            }.getOrDefault(body)
        }
        if (url.contains("bds/search/hot")) {
            return runCatching {
                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                if (data != null) {
                    val hotKeywords = data.optJSONArray("hot_keywords")
                    val words = data.optJSONArray("words")
                    if (hotKeywords != null) {
                        var i = hotKeywords.length() - 1
                        while (i >= 0) {
                            val item = hotKeywords.optJSONObject(i)
                            if (item != null && item.optString("schema").startsWith("bds://webview")) {
                                hotKeywords.remove(i)
                                words?.remove(i)
                            }
                            i--
                        }
                    }
                }
                json.toString()
            }.getOrDefault(body)
        }
        return body
    }
}
