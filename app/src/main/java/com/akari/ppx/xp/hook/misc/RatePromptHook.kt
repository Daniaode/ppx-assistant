@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import de.robv.android.xposed.XposedBridge

/**
 * 去掉「喜欢皮皮虾吗？请给一点鼓励 / 提交 / 取消」这个求好评弹窗。
 *
 * 该弹窗不是定时触发，而是刷一段时间视频后由业务触发，且文案来自资源
 * `string/bul`（资源名已被混淆，布局路径也被资源混淆），无法按类名/布局名定位。
 *
 * 因此采用**按文案识别**：Dialog 显示后检查其文案，命中就立即 close。
 * 优点：与具体实现类无关，宿主升级后只要文案不变就继续有效。
 */
class RatePromptHook : BaseHook {
    override fun onHook() {
        runCatching {
            "android.app.Dialog".findClass(cl).hookAfterMethod("show") { param ->
                val dialog = param.thisObject as? Dialog ?: return@hookAfterMethod
                val decor = runCatching { dialog.window?.decorView }.getOrNull() ?: return@hookAfterMethod
                if (containsRatingText(decor)) {
                    runCatching { dialog.dismiss() }
                    log("dismissed rating prompt")
                }
            }
            log("Dialog.show hooked")
        }.onFailure { log("hook failed: $it") }
    }

    private fun containsRatingText(v: View): Boolean {
        if (v is TextView) {
            val text = v.text?.toString().orEmpty()
            if (text.contains("喜欢皮皮虾") || text.contains("给一点鼓励")) return true
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (containsRatingText(v.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun log(msg: String) {
        Log.i("RatePrompt $msg")
        runCatching { XposedBridge.log("PPX RatePrompt: $msg") }
    }
}
