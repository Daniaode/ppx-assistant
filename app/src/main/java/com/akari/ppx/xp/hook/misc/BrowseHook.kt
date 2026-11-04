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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.detailPagerFragClass
import com.akari.ppx.xp.hook.BaseHook

/**
 * 自动刷视频。
 *
 * 推荐页：DetailPagerFragment + ViewPager，钩 onPageSelected 后 setCurrentItem。
 * 视频页：ImmersiveFeedListFragment + RecyclerView（竖向翻页）。
 *
 * 视频页的关键结论（真机反复验证后）：
 *  1. 视频是**循环播放**的，所以「播放完成回调」d() 永远不会来；
 *     必须用「当前播放位置 >= 时长 - 500ms」判定到达结尾。
 *  2. 调 smoothScrollToPosition / smoothScrollBy 在这套自定义 SnapHelper 上不可靠，
 *     改用注入真实触摸事件（DOWN -> MOVE -> UP）模拟用户上滑，再以滚动兜底。
 *  3. 位置不再依赖 a(int) 翻页回调，用生命周期跟踪当前可见 Fragment。
 */
class BrowseHook : BaseHook {
    override fun onHook() {
        val isAutoBrowse: Boolean = XPrefs("auto_browse")
        val isDelayHandoff: Boolean = XPrefs("video_delay_handoff")
        val browseFreq: Int = XPrefs<String>("auto_browse_frequency").let {
            it.toIntOrNull() ?: 3000
        }
        val anyEnabled = isAutoBrowse || isDelayHandoff

        Log.i("BrowseHook init: autoBrowse=$isAutoBrowse delayHandoff=$isDelayHandoff freq=$browseFreq")

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
                    Log.i("BrowseHook rec: viewPager=${viewPager != null}")
                }
            }
        }

        // 推荐页：定时切换下一条（用户原功能，不改动）
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

        // 推荐页：视频播放完再切换
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

        // 视频页（沉浸式信息流）
        if (anyEnabled) {
            hookImmersiveTracking()
            hookSupVideoView()
        }
    }

    // ------------------------------------------------------------------
    // 视频页：跟踪沉浸页 + 监听播放器
    // ------------------------------------------------------------------

    private fun hookImmersiveTracking() {
        IMMERSIVE_FRAGMENTS.forEach { clsName ->
            runCatching {
                val cls = clsName.findClass(cl)
                cls.hookAfterMethod("onViewCreated", View::class.java, Bundle::class.java) { param ->
                    val frag = param.thisObject as? Fragment ?: return@hookAfterMethod
                    activeImmersive = frag
                    Log.i("BrowseHook immersive created: ${clsName.substringAfterLast('.')}")
                    startEndWatcher(frag)
                }
                cls.hookBeforeMethod("onDestroyView") { param ->
                    val owner = param.thisObject
                    if (activeImmersive === owner) activeImmersive = null
                    (owner as? Fragment)?.let { stopEndWatcher(it) }
                }
                Log.i("BrowseHook hooked immersive: ${clsName.substringAfterLast('.')}")
            }.onFailure { Log.e("BrowseHook tracking [$clsName]: $it") }
        }
    }

    /** 捕获 SupVideoView 实例，用于轮询播放位置。 */
    private fun hookSupVideoView() {
        val svv = "com.sup.android.supvideoview.videoview.SupVideoView"
        runCatching {
            val cls = svv.findClass(cl)
            // 开始播放 / seek 起播：记录当前实例
            cls.declaredMethods
                .filter { it.name == "a" && it.parameterTypes.isNotEmpty() }
                .filter { it.parameterTypes[0] == java.lang.Long.TYPE }
                .forEach { m ->
                    m.hookAfterMethod { param ->
                        currentVideoView = param.thisObject
                    }
                }
            // 播放完成回调（循环播放时可能永远不来，保留作兜底）
            cls.hookAfterMethod("d") { param ->
                currentVideoView = param.thisObject
                Log.i("BrowseHook SupVideoView.d fired")
                onPlayFinished("d()")
            }
            Log.i("BrowseHook hooked SupVideoView")
        }.onFailure { Log.e("BrowseHook SupVideoView: $it") }
    }

    private fun startEndWatcher(frag: Fragment) {
        stopEndWatcher(frag)
        val r = object : Runnable {
            override fun run() {
                runCatching { checkVideoEnd(frag) }
                mainHandler.postDelayed(this, 500L)
            }
        }
        watchers[frag] = r
        mainHandler.postDelayed(r, 500L)
    }

    private fun stopEndWatcher(frag: Fragment) {
        watchers.remove(frag)?.let { mainHandler.removeCallbacks(it) }
    }

    /** 位置 >= 时长 - 500ms 视为播放到结尾（循环播放也能抓到）。 */
    private fun checkVideoEnd(frag: Fragment) {
        if (!frag.isResumed) return
        val v = currentVideoView ?: return
        val pos = v.callMethodOrNullAs<Long>("getCurrentPosition") ?: return
        val dur = v.callMethodOrNullAs<Long>("getDuration") ?: return
        if (dur <= 0L || pos <= 0L) return
        if (pos < dur - 500L) return
        Log.i("BrowseHook end detected pos=$pos dur=$dur")
        onPlayFinished("position")
    }

    private fun onPlayFinished(source: String) {
        val frag = activeImmersive ?: return
        if (!frag.isAdded || frag.view == null) return
        val now = System.currentTimeMillis()
        if (now - lastAdvanceAt < 1_500L) return
        lastAdvanceAt = now
        val rv = findRecyclerView(frag) ?: run {
            Log.e("BrowseHook advance: RecyclerView not found")
            return
        }
        Log.i("BrowseHook advance triggered by $source")
        swipeUp(rv)
    }

    /**
     * 注入真实上滑手势。
     * 沉浸流是自定义 SnapHelper，直接调滚动方法常被拦掉；触摸事件走用户路径最可靠。
     */
    private fun swipeUp(rv: RecyclerView) {
        val w = rv.width
        val h = rv.height
        if (w <= 0 || h <= 0) return
        val x = w / 2f
        val yStart = h * 0.85f
        val yEnd = h * 0.15f
        val t0 = SystemClock.uptimeMillis()
        val events = listOf(
            MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, yStart, 0),
            MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_MOVE, x, (yStart + yEnd) / 2f, 0),
            MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_MOVE, x, yEnd, 0),
            MotionEvent.obtain(t0, t0 + 120, MotionEvent.ACTION_UP, x, yEnd, 0)
        )
        val before = firstVisible(rv)
        events.forEach { e ->
            runCatching { rv.dispatchTouchEvent(e) }
            e.recycle()
        }
        Log.i("BrowseHook swipe injected (before=$before)")
        // 手势没生效时兜底整页滚动
        rv.postDelayed({
            val after = firstVisible(rv)
            if (after == before) {
                Log.i("BrowseHook swipe ineffective, fallback scrollBy")
                runCatching { rv.smoothScrollBy(0, h) }
            } else {
                Log.i("BrowseHook swipe ok (after=$after)")
            }
        }, 700L)
    }

    private fun firstVisible(rv: RecyclerView): Int {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return -1
        return runCatching { lm.findFirstVisibleItemPosition() }.getOrDefault(-1)
    }

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

    companion object {
        var viewPager: Any? = null
        var isAuto = true
        private var activeImmersive: Fragment? = null
        private var currentVideoView: Any? = null
        private var lastAdvanceAt = 0L

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val watchers = HashMap<Fragment, Runnable>()

        private val IMMERSIVE_FRAGMENTS = listOf(
            "com.sup.superb.feedui.view.ImmersiveFeedListFragment",
            "com.sup.superb.feedui.view.CardImmersiveFeedFragment",
            "com.sup.superb.feedui.view.SecondaryImmersiveListFragment",
            "com.sup.superb.feedui.view.CollectionFeedFragment"
        )
    }
}
