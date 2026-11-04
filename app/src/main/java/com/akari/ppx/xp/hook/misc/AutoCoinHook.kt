@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook
import java.lang.reflect.Proxy

/**
 * 自动领金币：宿主启动并完成登录初始化后，自动触发每日签到领取金币。
 *
 * 6.6.0 的 MoneyExcitingService 保持可读类名（未混淆），签到入口为
 * `acquireDailySignIn(IMoneyFinishTaskCallback)`；回调接口在 dex 中为
 * `com.sup.android.m_integral.money.a`（6.5.x 为 IMoneyFinishTaskCallback）。
 * 两个名字都尝试，失败时静默跳过，不影响旧版本。
 */
class AutoCoinHook : SwitchHook("auto_collect_coins") {
    override fun onHook() {
        // 每日签到领金币 + 启动看视频领奖任务（多次重试覆盖登录较慢场景）
        runCatching {
            "com.sup.android.m_integral.MoneyExcitingService".findClass(cl).hookAfterMethod(
                "init", Boolean::class.java, Application::class.java
            ) { param ->
                val service = param.thisObject ?: return@hookAfterMethod
                val handler = Handler(Looper.getMainLooper())
                var attempt = 0
                val task = object : Runnable {
                    override fun run() {
                        attempt++
                        acquireDailySignIn(service)
                        if (attempt == 1) {
                            runCatching { service.callMethodOrNull("tryStartWatchVideoTask") }
                                .onFailure { Log.e("AutoCoinHook tryStartWatchVideoTask: $it") }
                        }
                        if (attempt < 4) handler.postDelayed(this, 8000L)
                    }
                }
                handler.postDelayed(task, 5000L)
            }
        }.onFailure { Log.e("AutoCoinHook attach failed: $it") }

        // 签到弹窗关闭后再次尝试，覆盖登录较慢的场景
        runCatching {
            "com.sup.android.m_integral.MoneyExcitingService".findClass(cl).hookAfterMethod(
                "notifySignInDialogFinish"
            ) { param ->
                val service = param.thisObject ?: return@hookAfterMethod
                Handler(Looper.getMainLooper()).postDelayed({
                    acquireDailySignIn(service)
                }, 1500L)
            }
        }.onFailure { Log.e("AutoCoinHook attach2 failed: $it") }
    }

    private fun acquireDailySignIn(service: Any) {
        runCatching {
            val callbackClass = listOf(
                "com.sup.android.m_integral.money.a",
                "com.sup.android.m_integral.money.IMoneyFinishTaskCallback"
            ).firstNotNullOfOrNull { name ->
                runCatching { name.findClass(cl) }.getOrNull()
            } ?: run {
                Log.w("AutoCoinHook callback class not found")
                return
            }
            val callback = Proxy.newProxyInstance(cl, arrayOf(callbackClass)) { _, method, args ->
                if (method.name == "a") {
                    Log.i("AutoCoinHook signIn result args=${args?.toList()}")
                }
                null
            }
            service.callMethodOrNull("acquireDailySignIn", callback)
            Log.i("AutoCoinHook signIn requested")
        }.onFailure { Log.e("AutoCoinHook signIn failed: $it") }
    }
}
