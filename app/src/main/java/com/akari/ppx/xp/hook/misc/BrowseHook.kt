@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.BuildConfig
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.detailPagerFragClass
import com.akari.ppx.xp.hook.BaseHook
import de.robv.android.xposed.XposedBridge

/**
 * 自动刷视频。
 *
 * 推荐页：DetailPagerFragment + ViewPager（原逻辑，未改动）。
 * 视频页：ImmersiveFeedListFragment + RecyclerView（竖向翻页）。
 *
 * 视频页已确认的事实：
 *  1. SupVideoView.d()（ISupPlayerListener 播放完成）**会触发**——
 *     应用日志同一时刻出现 ImmersiveVideoGoldBusinessComponent newState=5。
 *  2. 之前切不动的原因：依赖 activeImmersive（由生命周期跟踪赋值）拿目标列表，
 *     跟踪没命中就直接静默返回。
 *     本版改为**从 SupVideoView 自身沿 View 树向上找 RecyclerView**，不依赖任何跟踪。
 *  3. 这类沉浸流用自定义 SnapHelper，直接调滚动方法不可靠，
 *     改为注入真实触摸事件（DOWN -> MOVE -> UP）。
 *
 * 日志同时写 android.util.Log 与 XposedBridge，避免单一 sink 丢日志。
 */
class BrowseHook : BaseHook {
    override fun onHook() {
        val isAutoBrowse: Boolean = XPrefs("auto_browse")
        val isDelayHandoff: Boolean = XPrefs("video_delay_handoff")
        val browseFreq: Int = XPrefs<String>("auto_browse_frequency").let {
            it.toIntOrNull() ?: 3000
        }
        val anyEnabled = isAutoBrowse || isDelayHandoff

        xlog("init v=${BuildConfig.VERSION_NAME} auto=$isAutoBrowse delay=$isDelayHandoff freq=$browseFreq")

        // 推荐页 viewPager 捕获
        anyEnabled.check(true) {
            detailPagerFragClass?.hookAfterMethod(
                "onCreateView",
                LayoutInflater::class.java,
                ViewGroup::class.java,
                Bundle::class.java
            ) { param ->
                detailPagerFragClass?.declaredFields?.firstOrNull { f ->
                    f.type.name == "com.bytedance.ies.uikit.viewpager.SwipeControlledViewPager"
                }?.name.let { f ->
                    viewPager = param.thisObject.getObjectField(f)
                }
            }
        }

        // 推荐页：定时切换下一条（原功能，未改动）
        isAutoBrowse.check(true) {
            detailPagerFragClass?.hookAfterMethod("onPageSelected", Int::class.java) { param ->
                if (!isAuto) {
                    isAuto = true
                    return@hookAfterMethod
                }
                refreshViewPager(param.thisObject)
                val position = param.args[0] as Int
                val shouldSwitch = if (isDelayHandoff) {
                    val feedCell = findFeedCell(param.thisObject, position)
                    feedCell != null && "com.sup.android.mi.feed.repo.bean.cell.NoteFeedItem"
                        .findClass(cl)
                        .isInstance(feedCell.callMethodOrNull("getFeedItem"))
                } else {
                    true
                }
                if (shouldSwitch) {
                    viewPager?.callMethod("postDelayed", object : Runnable {
                        override fun run() {
                            switchToNext()
                        }
                    }, (if (1 == position) 1000 else browseFreq).toLong())
                }
            }
        }

        // 推荐页：视频播放完再切换（原功能，未改动）
        isDelayHandoff.check(true) {
            val videoHolderClass = "com.sup.superb.video.viewholder.a".findClass(cl)
            videoHolderClass.hookAfterMethod("k_", Int::class.java) { param ->
                if (5 == param.args[0] as Int) {
                    viewPager?.callMethod("postDelayed", object : Runnable {
                        override fun run() {
                            switchToNext()
                        }
                    }, 0L)
                }
            }
        }

        if (anyEnabled) {
            hookImmersiveTracking()
            hookFeedRecycler()
            hookSupVideoView()
        }
    }

    /**
     * 直接让宿主把列表交出来。
     * BaseFeedFragment.l() 是 public final，宿主内部到处在用；
     * 钩它就能稳定拿到各个 feed 的 RecyclerView，不依赖 View 树猜测。
     */
    private fun hookFeedRecycler() {
        runCatching {
            "com.sup.superb.feedui.view.BaseFeedFragment".findClass(cl)
                .hookAfterMethod("l") { param ->
                    val rv = param.result as? RecyclerView ?: return@hookAfterMethod
                    feedRecycler = rv
                    if (!loggedRecycler) {
                        loggedRecycler = true
                        xlog("captured feed RecyclerView ${rv.width}x${rv.height}")
                    }
                }
            xlog("BaseFeedFragment.l hooked")
        }.onFailure { xlog("BaseFeedFragment.l failed: $it") }
    }

    /** 生命周期跟踪沉浸页，作为找不到祖先列表时的兼底。 */
    private fun hookImmersiveTracking() {
        IMMERSIVE_FRAGMENTS.forEach { clsName ->
            runCatching {
                val cls = clsName.findClass(cl)
                cls.hookAfterMethod("onViewCreated", View::class.java, Bundle::class.java) { param ->
                    (param.thisObject as? Fragment)?.let { activeImmersive = it }
                }
                cls.hookBeforeMethod("onDestroyView") { param ->
                    val owner = param.thisObject
                    if (activeImmersive === owner) activeImmersive = null
                }
            }.onFailure { xlog("tracking[$clsName] failed: $it") }
        }
    }

    // ------------------------------------------------------------------
    // 视频页：播放完成 -> 上滑
    // ------------------------------------------------------------------

    private fun hookSupVideoView() {
        val name = "com.sup.android.supvideoview.videoview.SupVideoView"
        runCatching {
            val cls = name.findClass(cl)
            // 播放完成回调：来源就是正在播放的控件，可直接沿 View 树找到列表
            cls.hookAfterMethod("d") { param ->
                val self = param.thisObject
                currentVideoView = self
                xlog("SupVideoView.d fired")
                advanceFrom(self, "d()")
            }
            // 起播/seek 时记录实例，供位置轮询使用
            cls.declaredMethods
                .filter { it.name == "a" && it.parameterTypes.isNotEmpty() }
                .filter { it.parameterTypes[0] == java.lang.Long.TYPE }
                .forEach { m -> m.hookAfterMethod { param -> currentVideoView = param.thisObject } }
            xlog("SupVideoView hooked")
        }.onFailure { xlog("SupVideoView hook failed: $it") }
    }

    /**
     * 从播放器控件沿 View 树向上找 RecyclerView，找不到再退回跟踪到的 Fragment。
     */
    private fun advanceFrom(source: Any?, tag: String) {
        val rv = (source as? View)?.let { findAncestorRecycler(it) }
            ?: feedRecycler?.takeIf { it.isShown }
            ?: activeImmersive?.let { findRecyclerView(it) }
            ?: (source as? View)?.let { findTargetRecycler(it) }
        if (rv == null) {
            xlog("advance[$tag]: no RecyclerView (src=${source?.javaClass?.simpleName}," +
                "feed=${feedRecycler != null}, frag=${activeImmersive != null})")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAdvanceAt < 1_500L) {
            xlog("advance[$tag]: debounced")
            return
        }
        lastAdvanceAt = now
        xlog("advance[$tag]: rv h=${rv.height} pos=${firstVisible(rv)}")
        swipeUp(rv)
    }

    /**
     * 视频控件未必在 RecyclerView 的子树里（可能挂在独立覆盖层）。
     * 先向上找祖先；找不到就从根视图向下枚举，取可见且面积最大的那个（视频页列表是全屏）。
     */
    private fun findTargetRecycler(v: View): RecyclerView? {
        findAncestorRecycler(v)?.let { return it }
        val candidates = ArrayList<RecyclerView>()
        runCatching { collectRecyclers(v.rootView, candidates) }
        val usable = candidates.filter {
            it.isShown && it.height > 0 && (it.adapter?.itemCount ?: 0) > 1
        }
        if (usable.isEmpty()) return null
        xlog("rv candidates=${usable.size} sizes=" + usable.joinToString { "${it.width}x${it.height}" })
        return usable.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun collectRecyclers(v: View, out: MutableList<RecyclerView>) {
        if (v is RecyclerView) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectRecyclers(v.getChildAt(i), out)
        }
    }

    private fun findAncestorRecycler(v: View): RecyclerView? {
        var p: ViewParent? = v.parent
        var guard = 0
        while (p != null && guard++ < 50) {
            if (p is RecyclerView) return p
            p = p.parent
        }
        return null
    }

    private fun swipeUp(rv: RecyclerView) {
        val w = rv.width
        val h = rv.height
        if (w <= 0 || h <= 0) {
            xlog("swipe: invalid size ${w}x$h")
            return
        }
        val x = w / 2f
        val yStart = h * 0.85f
        val yEnd = h * 0.15f
        val t0 = SystemClock.uptimeMillis()
        val before = firstVisible(rv)
        val events = listOf(
            MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, yStart, 0),
            MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_MOVE, x, (yStart + yEnd) / 2f, 0),
            MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_MOVE, x, yEnd, 0),
            MotionEvent.obtain(t0, t0 + 120, MotionEvent.ACTION_UP, x, yEnd, 0)
        )
        events.forEach { e ->
            runCatching { rv.dispatchTouchEvent(e) }
            e.recycle()
        }
        xlog("swipe injected before=$before")
        rv.postDelayed({
            val after = firstVisible(rv)
            if (after == before) {
                xlog("swipe ineffective, fallback scrollBy")
                runCatching { rv.smoothScrollBy(0, h) }
            } else {
                xlog("swipe ok after=$after")
            }
        }, 700L)
    }

    private fun firstVisible(rv: RecyclerView): Int {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return -1
        return runCatching { lm.findFirstVisibleItemPosition() }.getOrDefault(-1)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun findRecyclerView(frag: Any): RecyclerView? {
        (frag.callMethodOrNull("l") as? RecyclerView)?.let { return it }
        (frag.callMethodOrNull("getMainRecyclerView") as? RecyclerView)?.let { return it }
        var c: Class<*>? = frag.javaClass
        while (c != null && c != Any::class.java) {
            val hit = runCatching {
                c!!.declaredFields.firstOrNull { it.name == "f" }?.let { f ->
                    f.isAccessible = true
                    f.get(frag) as? RecyclerView
                }
            }.getOrNull()
            if (hit != null) return hit
            c = c.superclass
        }
        (frag.callMethodOrNull("getView") as? ViewGroup)?.let { return findRecyclerIn(it) }
        return null
    }

    private fun findRecyclerIn(v: View): RecyclerView? {
        if (v is RecyclerView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findRecyclerIn(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun refreshViewPager(owner: Any) {
        runCatching {
            owner.javaClass.declaredFields.firstOrNull { f ->
                f.type.name == "com.bytedance.ies.uikit.viewpager.SwipeControlledViewPager"
            }?.apply { isAccessible = true }?.get(owner)?.let { viewPager = it }
        }
    }

    private fun findFeedCell(owner: Any, index: Int): Any? {
        val fields = owner.javaClass.declaredFields.filter { it.type == ArrayList::class.java }
        val preferred = fields.firstOrNull { f ->
            runCatching { f.genericType.typeName.contains("AbsFeedCell") }.getOrDefault(false)
        } ?: fields.firstOrNull()
        for (f in listOfNotNull(preferred).plus(fields)) {
            runCatching {
                f.isAccessible = true
                val list = f.get(owner) as? ArrayList<*> ?: return@runCatching
                if (index >= 0 && index < list.size) {
                    return list[index]
                }
            }
        }
        return null
    }

    private fun switchToNext() {
        val vp = viewPager ?: return
        val current = vp.callMethodOrNullAs<Int>("getCurrentItem") ?: return
        vp.callMethodOrNull("setCurrentItem", current + 1)
    }

    /** 双 sink 日志：logcat + Xposed 模块日志，避免单一通道丢失。 */
    private fun xlog(msg: String) {
        Log.i("BrowseHook $msg")
        runCatching { XposedBridge.log("PPX BrowseHook: $msg") }
    }

    companion object {
        var viewPager: Any? = null
        var isAuto = true
        private var activeImmersive: Fragment? = null
        private var currentVideoView: Any? = null
        private var feedRecycler: RecyclerView? = null
        private var loggedRecycler = false
        private var lastAdvanceAt = 0L

        private val IMMERSIVE_FRAGMENTS = listOf(
            "com.sup.superb.feedui.view.ImmersiveFeedListFragment",
            "com.sup.superb.feedui.view.CardImmersiveFeedFragment",
            "com.sup.superb.feedui.view.SecondaryImmersiveListFragment",
            "com.sup.superb.feedui.view.CollectionFeedFragment"
        )
    }
}
