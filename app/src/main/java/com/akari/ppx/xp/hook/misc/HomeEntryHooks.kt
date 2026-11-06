@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.hookAfterAllConstructors
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.replaceMethod
import com.akari.ppx.utils.replaceAfterAllMethods
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

/**
 * The bottom bar is built by MainBottomBarController and later re-shaped by a
 * server-driven tab config that swaps indicator views by position, so specs are
 * hidden at their common source instead: the indicator factory b.a(...) is the
 * only place that creates a tab view, and it receives the spec tag (args[1])
 * plus the resolved label (args[2]).
 */
/**
 * 隐藏底栏 Tab。
 *
 * 只在创建时设 GONE 不够：打开评论再返回、或切任务回来时，宿主会重新
 * setVisibility(VISIBLE) 把隐藏状态覆盖掉（实测现象：底栏又出现，切一次任务又好了）。
 * 因此把目标视图记下来，并全局压 setVisibility ，保证它一直是 GONE。
 */
private fun hideBottomTabs(vararg tags: String) {
    ensureVisibilityGuard()
    "com.sup.android.base.main.bottom.b".findClass(cl).hookAfterMethod(
        "a",
        android.content.Context::class.java,
        String::class.java,
        String::class.java,
        Int::class.javaPrimitiveType
    ) { param ->
        val tag = param.args[1]?.toString()
        val label = param.args[2]?.toString()
        if (tag in tags || label in tags) {
            val view = param.result as? View ?: return@hookAfterMethod
            hiddenTabViews.add(view)
            view.visibility = View.GONE
        }
    }
}

/** 被要求隐藏的 Tab 视图（弱引用，避免泄漏）。 */
private val hiddenTabViews: MutableSet<View> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

@Volatile
private var visibilityGuardInstalled = false

/** 全局兼底：这些视图一旦被重新显示，立即重新隐藏。 */
private fun ensureVisibilityGuard() {
    if (visibilityGuardInstalled) return
    synchronized(HomeEntryHooksGuard) {
        if (visibilityGuardInstalled) return
        runCatching {
            "android.view.View".findClass(cl).hookBeforeMethod(
                "setVisibility",
                Int::class.javaPrimitiveType
            ) { param ->
                val view = param.thisObject as? View ?: return@hookBeforeMethod
                if (param.args[0] as? Int == View.VISIBLE && hiddenTabViews.contains(view)) {
                    param.args[0] = View.GONE
                }
            }
            visibilityGuardInstalled = true
        }
    }
}

private val HomeEntryHooksGuard = Any()

class TopicTabHook : SwitchHook("hide_bottom_topic_tab") {
    // Slot two is "discovery" (话题) or "task" (福利中心) depending on the host's
    // coin-system flag, so both tags hide the same position.
    override fun onHook() = hideBottomTabs("discovery", "task", "话题", "福利")
}

class MessageTabHook : SwitchHook("hide_bottom_message_tab") {
    override fun onHook() = hideBottomTabs("message", "消息")
}

/** The home top-bar game icon is created solely by MiniGameHelper (dex
 * `feedui.util.aa`; constructor receives the stub and icon view ids). */
class HomeGameEntryHook : SwitchHook("hide_home_game_entry") {
    override fun onHook() {
        "com.sup.superb.feedui.util.aa".findClass(cl).hookAfterAllConstructors { param ->
            val container = param.args[0] as? View ?: return@hookAfterAllConstructors
            val stubId = param.args[1] as? Int ?: return@hookAfterAllConstructors
            val iconId = param.args[2] as? Int ?: return@hookAfterAllConstructors
            container.findViewById<View>(stubId)?.visibility = View.GONE
            container.findViewById<View>(iconId)?.visibility = View.GONE
        }
    }
}

/** The draggable coin floating ball above the vote row is MoneyIconController
 * (dex `m_integral.view.d`) wrapping `MoneyExcitingUtil.addViewToParent`. */
class CoinFloatBallHook : SwitchHook("hide_coin_float_ball") {
    override fun onHook() {
        "com.sup.android.m_integral.view.d".findClass(cl).hookAfterAllConstructors { param ->
            (param.args[0] as? View)?.visibility = View.GONE
        }
        // 6.5.x keeps the readable name MoneyExcitingUtil; 6.6.0 obfuscates it to money.c.
        listOf(
            "com.sup.android.m_integral.money.MoneyExcitingUtil",
            "com.sup.android.m_integral.money.c"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterMethod(
                    "addViewToParent",
                    View::class.java,
                    ViewGroup::class.java
                ) { param ->
                    (param.args[0] as? View)?.visibility = View.GONE
                }
            }
        }
    }
}

/** The floating red-packet / watch-duration widget on the home feed. pyhelper
 * locates it as BaseDurationView inside the m_duration package; in 6.6.0 the
 * container keeps the readable name DurationLayout, so hide it on creation. */
class RedPacketFloatHook : SwitchHook("hide_red_packet") {
    override fun onHook() {
        // 6.6.x 首页右下角「红包/福利」= 招财猫(LuckyCat)弹窗系统。
        // 关键（dexdump 实测）：类名是可读的、未混淆；只有内部方法名被混淆。宁可错杀：
        //   1) 弹窗载体 LuckyCatPopupFragment（可读类名，DialogFragment）：阻断其展示
        //   2) 弹窗服务 LuckyCatLynxPopupService = popup.b，showPopup 可读 → false
        //   3) 路由拦截 LuckyCatRouterInterceptor = base.luckycat.i，matchInterceptRules 可读 → false
        //   4) 隐藏右下角红包悬浮气泡 container.s/g/t/h
        val luckyCatFrag = "com.bytedance.ug.sdk.luckycat.impl.popup.LuckyCatPopupFragment"
        runCatching {
            luckyCatFrag.findClass(cl).apply {
                replaceAfterAllMethods("onCreateView") { null }
                replaceAfterAllMethods("onCreateDialog") { null }
                hookAfterMethod("onViewCreated", View::class.java, "android.os.Bundle") { param ->
                    runCatching { param.thisObject?.callMethodOrNull("dismissAllowingStateLoss") }
                    runCatching { (param.args.getOrNull(0) as? View)?.visibility = View.GONE }
                }
            }
        }.onFailure { Log.e("RedPacketFloatHook luckycat frag: $it") }
        runCatching {
            "com.bytedance.ug.sdk.luckycat.impl.popup.b".findClass(cl)
                .replaceAfterAllMethods("showPopup") { false }
        }.onFailure { Log.e("RedPacketFloatHook luckycat showPopup: $it") }
        runCatching {
            "com.sup.android.base.luckycat.i".findClass(cl)
                .replaceAfterAllMethods("matchInterceptRules") { false }
        }.onFailure { Log.e("RedPacketFloatHook luckycat matchInterceptRules: $it") }
        listOf(
            "com.bytedance.ug.sdk.luckycat.container.s",
            "com.bytedance.ug.sdk.luckycat.container.g",
            "com.bytedance.ug.sdk.luckycat.container.t",
            "com.bytedance.ug.sdk.luckycat.container.h"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterAllConstructors { param ->
                    runCatching {
                        val v = param.thisObject as? View
                        v?.visibility = View.GONE
                    }
                    runCatching {
                        param.thisObject?.callMethodOrNull("dismissAllowingStateLoss")
                    }
                }
            }
        }
        // 看视频得金币「光环计时 +20金币」悬浮控件（i_duration 层，之前漏掉的一层）：
        //   GoldLayoutWrapper(FrameLayout) 是外层容器，由 DetailPagerFragment /
        //   TagDetailPagerFragmentV2 通过 findViewById 从布局拿到，内部 addView 了
        //   DragRewardVideoLayout(运行时名 a) 和 CircularView(光环)。全部构造后 GONE。
        listOf(
            "com.sup.superb.i_duration.ui.widget.GoldLayoutWrapper",
            "com.sup.superb.i_duration.ui.widget.CircularView",
            "com.sup.superb.i_duration.ui.widget.a",
            "com.sup.superb.feedui.widget.PetFloatingWidget"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterAllConstructors { param ->
                    (param.thisObject as? View)?.visibility = View.GONE
                }
            }.onFailure { Log.e("RedPacketFloatHook duration $name: $it") }
        }

        // 决定性兜底：这些悬浮控件的显隐由业务代码动态 setVisibility(VISIBLE) 控制，
        // 光在构造时设 GONE 会被干掉。全局 hook View.setVisibility(int)，只要目标是
        // 这些类，就把参数强制改成 GONE——业务何时想显示都直接压回隐藏。
        runCatching {
            val badClassCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()
            View::class.java.hookBeforeMethod("setVisibility", Int::class.javaPrimitiveType) { param ->
                val v = param.thisObject as? View ?: return@hookBeforeMethod
                val bad = badClassCache.computeIfAbsent(v.javaClass) { c ->
                    val n = c.name
                    n.contains("i_duration") || n.contains("GoldLayout") ||
                        n.contains("CircularView") || n.contains("PetFloating") ||
                        n.contains("MoneyExciting") || n.contains("CoinFloat") ||
                        n.contains("DragReward") || n.contains("redpacket") ||
                        n.contains("RedPacket")
                }
                if (bad) param.args[0] = View.GONE
            }
        }.onFailure { Log.e("RedPacketFloatHook global setVisibility: $it") }
        // 招财狗(luckydog)挂件：showPendant 入口 no-op + 视图 GONE
        runCatching {
            "com.bytedance.ug.sdk.luckydog.task.pendant.b".findClass(cl).replaceMethod(
                "a",
                FrameLayout::class.java,
                FrameLayout.LayoutParams::class.java,
                Int::class.javaPrimitiveType,
                "com.bytedance.ug.sdk.luckydog.api.task.pendant.PendantStyle"
            ) { null }
        }.onFailure { Log.e("RedPacketFloatHook luckydog showPendant: $it") }
        listOf(
            "com.bytedance.ug.sdk.luckydog.api.task.pendant.b",
            "com.bytedance.ug.sdk.luckydog.api.task.pendant.a",
            "com.bytedance.ug.sdk.luckydog.task.pendant.d",
            "com.bytedance.ug.sdk.luckydog.task.newTimer.pendant.k",
            "com.bytedance.ug.sdk.luckydog.task.newTimer.counter.pendant.b",
            "com.bytedance.ug.sdk.luckydog.task.tasktimer.view.a",
            "com.bytedance.ug.sdk.luckydog.task.tasktimer.view.b"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterAllConstructors { param ->
                    (param.thisObject as? View)?.visibility = View.GONE
                }
            }
        }
        // 6.6.1「登录领金币」novelcoin2 ReaderBar（数据源与视图层双重拦截）
        listOf(
            "com.bytedance.novel.ug.novelcoin2.view.ReaderBarProxy",
            "com.bytedance.novel.ug.novelcoin2ui4ttlite.widget.ReaderBar"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterAllConstructors { param ->
                    (param.thisObject as? View)?.visibility = View.GONE
                }
            }
        }
        listOf(
            "com.bytedance.novel.ug.novelcoin2.view.mgr.NonReaderBarMgr",
            "com.bytedance.novel.ug.novelcoin2.view.mgr.NovelDailyReaderBarMgr",
            "com.bytedance.novel.ug.novelcoin2.view.mgr.ChapterReaderBarMgr"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterMethod(
                    "combineLatestApply",
                    "com.bytedance.novel.ug.novelcoin2.bean.TaskListRspModel",
                    Int::class.javaPrimitiveType
                ) { param ->
                    param.result = null
                }
            }
        }
        listOf(
            "com.sup.superb.m_duration.widget.DurationLayout",
            "com.sup.superb.m_duration.BaseDurationView"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterAllConstructors { param ->
                    (param.thisObject as? View)?.visibility = View.GONE
                }
            }
        }
        // 6.6.0 also builds the float view through the coin services; hide the
        // freshly added child right after it is attached to the parent.
        listOf(
            "com.sup.android.m_integral.MoneyExcitingService",
            "com.sup.android.m_integral.IntegralService"
        ).forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterMethod(
                    "addCoinVideoFloatView",
                    ViewGroup::class.java,
                    "androidx.lifecycle.LifecycleOwner",
                    "com.sup.android.superb.i_ad.interfaces.IAppMainActivity"
                ) { param ->
                    val parent = param.args.getOrNull(0) as? ViewGroup ?: return@hookAfterMethod
                    runCatching {
                        parent.getChildAt(parent.childCount - 1)?.visibility = View.GONE
                    }
                }
            }
        }
    }
}
