@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import com.akari.ppx.utils.Log
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import de.robv.android.xposed.XposedBridge

/**
 * 默认关闭皮皮虾的「流量/省电」类开关：
 *   - WIFI 下视频自动播放        auto_play_video_wifi
 *   - 非 WIFI 下视频自动播放     auto_play_video_not_wifi
 *   - 非 WIFI 下看直播           __local__auto_play_video_not_wifi_live_new
 *   - 内容自动刷新               __local__feed_auto_refresh_setting_enable
 *
 * 做法：钩住统一读取入口 SettingService.getValue(String, T, String...)，
 * 命中这几个键就直接返回 false。这样所有调用方（设置页开关、播放器、刷新逻辑）
 * 看到的值都是关闭，而且不修改用户的真实存储。
 */
class AutoPlayOffHook : BaseHook {
    override fun onHook() {
        val keys = setOf(
            "auto_play_video_wifi",
            "auto_play_video_not_wifi",
            "__local__auto_play_video_not_wifi_live_new",
            "__local__feed_auto_refresh_setting_enable"
        )
        runCatching {
            "com.sup.android.social.base.settings.SettingService".findClass(cl)
                .hookAfterMethod(
                    "getValue",
                    String::class.java,
                    Any::class.java,
                    Array<String>::class.java
                ) { param ->
                    val key = param.args[0] as? String ?: return@hookAfterMethod
                    if (key in keys) param.result = false
                }
            log("SettingService.getValue hooked")
        }.onFailure { log("hook failed: $it") }
    }

    private fun log(msg: String) {
        Log.i("AutoPlayOff $msg")
        runCatching { XposedBridge.log("PPX AutoPlayOff: $msg") }
    }
}
