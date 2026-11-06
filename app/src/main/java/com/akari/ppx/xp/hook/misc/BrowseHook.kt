@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import com.akari.ppx.BuildConfig
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.detailPagerFragClass
import com.akari.ppx.xp.hook.BaseHook
import de.robv.android.xposed.XposedBridge

/**
 * 自动刷视频。
 *
 * 推荐页：DetailPagerFragment + ViewPager（原逻辑，未改动）。
 * 视频页：ImmersiveFeedListFragment，纵向列表是 GestureObservableRecyclerView。
 *
 * ★ 关键教训（0.0.14 ~ 0.0.25 全部失败的真正原因）：
 * 模块 dex 里自带了一份 androidx 类（R8 保留），与宿主 App 的是**不同 ClassLoader
 * 加载的同名类**。因此模块代码里：
 *   `obj is androidx.recyclerview.widget.RecyclerView` → false
 *   `obj as? androidx.fragment.app.Fragment`          → null
 * 而 `obj as? android.view.View` 永远正常（View 是启动类，全进程共享）。
 *
 * 所以本文件**不使用任何 androidx 类型**：
 *   - 判断列表：沿继承链比对类名 "androidx.recyclerview.widget.RecyclerView"
 *   - 列表相关方法（smoothScrollBy / layoutManager）走反射
 *   - Fragment 相关一律反射（isResumed / view）
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

        // 推荐页：定时切换下一条（原功能，不改动）
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

        // 推荐页：视频播放完再切换（原功能，不改动）
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

    // ------------------------------------------------------------------
    // 视频页
    // ------------------------------------------------------------------

    /** 记录沉浸页实例（用 Any，避免 androidx Fragment 的类型不认问题）。 */
    private fun hookImmersiveTracking() {
        IMMERSIVE_FRAGMENTS.forEach { clsName ->
            runCatching {
                val cls = clsName.findClass(cl)
                cls.hookAfterMethod("onViewCreated", View::class.java, Bundle::class.java) { param ->
                    activeImmersive = param.thisObject
                }
                cls.hookBeforeMethod("onDestroyView") { param ->
                    if (activeImmersive === param.thisObject) activeImmersive = null
                }
            }.onFailure { xlog("tracking[$clsName] failed: $it") }
        }
    }

    /** 直接捕获 BaseFeedFragment.l() 返回的列表（同样用 Any + 反射）。 */
    private fun hookFeedRecycler() {
        runCatching {
            "com.sup.superb.feedui.view.BaseFeedFragment".findClass(cl)
                .hookAfterMethod("l") { param ->
                    val result = param.result
                    if (!isRecycler(result)) return@hookAfterMethod
                    feedRecycler = result
                    if (!loggedRecycler) {
                        loggedRecycler = true
                        val w = result.callMethodOrNullAs<Int>("getWidth") ?: -1
                        val h = result.callMethodOrNullAs<Int>("getHeight") ?: -1
                        xlog("captured feed RecyclerView ${w}x$h (${result?.javaClass?.name})")
                    }
                }
            xlog("BaseFeedFragment.l hooked")
        }.onFailure { xlog("BaseFeedFragment.l failed: $it") }
    }

    private fun hookSupVideoView() {
        val name = "com.sup.android.supvideoview.videoview.SupVideoView"
        runCatching {
            val cls = name.findClass(cl)
            cls.hookAfterMethod("d") { param ->
                val self = param.thisObject
                currentVideoView = self
                xlog("SupVideoView.d fired")
                advanceFrom(self, "d()")
            }
            cls.declaredMethods
                .filter { it.name == "a" && it.parameterTypes.isNotEmpty() }
                .filter { it.parameterTypes[0] == java.lang.Long.TYPE }
                .forEach { m -> m.hookAfterMethod { param -> currentVideoView = param.thisObject } }
            xlog("SupVideoView hooked")
        }.onFailure { xlog("SupVideoView hook failed: $it") }
    }

    private fun advanceFrom(source: Any?, tag: String) {
        val srcView = source as? View
        if (srcView != null) dumpScene(srcView, tag)

        // 纵向列表优先；横向 ViewPager 一律不用（会导致横着切）
        var via = "ancestor"
        var rv = srcView?.let { findAncestorRecycler(it) }
        if (rv == null) {
            via = "feed"
            rv = feedRecycler?.takeIf { isShown(it) }
        }
        if (rv == null) {
            via = "fragment"
            rv = activeImmersive?.let { findRecyclerView(it) }
        }
        if (rv == null) {
            via = "rootScan"
            rv = srcView?.let { scanRoot(it) }
        }
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
        xlog("advance[$tag]: via=$via rv=${rv.javaClass.simpleName} h=${rv.callMethodOrNullAs<Int>("getHeight")} pos=${firstVisible(rv)}")
        swipeUp(rv)
    }

    // ------------------------------------------------------------------
    // 类型判断（不依赖 androidx 类型）
    // ------------------------------------------------------------------

    /** 沿继承链比对类名，绕开「同名不同 ClassLoader」的问题。 */
    private fun isRecycler(o: Any?): Boolean {
        var c: Class<*>? = o?.javaClass ?: return false
        while (c != null) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
        }
        return false
    }

    private fun isShown(o: Any?): Boolean =
        runCatching { o?.callMethodOrNullAs<Boolean>("isShown") == true }.getOrDefault(false)

    private fun isResumed(o: Any?): Boolean =
        runCatching { o?.callMethodOrNullAs<Boolean>("isResumed") == true }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 查找列表
    // ------------------------------------------------------------------

    private fun findAncestorRecycler(v: View): Any? {
        var p: ViewParent? = v.parent
        var guard = 0
        while (p != null && guard++ < 60) {
            if (isRecycler(p)) return p
            p = p.parent
        }
        return null
    }

    private fun findRecyclerView(frag: Any): Any? {
        if (!isResumed(frag)) return null
        (frag.callMethodOrNull("l") as? Any)?.let { if (isRecycler(it)) return it }
        (frag.callMethodOrNull("getMainRecyclerView"))?.let { if (isRecycler(it)) return it }
        var c: Class<*>? = frag.javaClass
        while (c != null && c != Any::class.java) {
            val hit = runCatching {
                c!!.declaredFields.firstOrNull { it.name == "f" }?.let { f ->
                    f.isAccessible = true
                    f.get(frag)
                }
            }.getOrNull()
            if (isRecycler(hit)) return hit
            c = c.superclass
        }
        (frag.callMethodOrNull("getView") as? View)?.let { return scanTree(it) }
        return null
    }

    private fun scanRoot(v: View): Any? = scanTree(v.rootView)

    private fun scanTree(v: View): Any? {
        if (isRecycler(v) && isShown(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                scanTree(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun firstVisible(rv: Any): Int {
        val lm = rv.callMethodOrNull("getLayoutManager") ?: return -1
        return runCatching { lm.callMethodOrNullAs<Int>("findFirstVisibleItemPosition") }.getOrNull() ?: -1
    }

    // ------------------------------------------------------------------
    // 手势
    // ------------------------------------------------------------------

    private fun swipeUp(rv: Any) {
        val v = rv as? View ?: return   // View 是启动类，跨 ClassLoader 也通用
        val w = v.width
        val h = v.height
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
            runCatching { v.dispatchTouchEvent(e) }
            e.recycle()
        }
        xlog("swipe injected before=$before")
        v.postDelayed({
            val after = firstVisible(rv)
            if (after == before) {
                xlog("swipe ineffective, fallback scrollBy")
                runCatching { rv.callMethodOrNull("smoothScrollBy", 0, h) }
            } else {
                xlog("swipe ok after=$after")
            }
        }, 700L)
    }

    private fun dumpScene(v: View, tag: String) {
        if (sceneLogged) return
        sceneLogged = true
        runCatching {
            val sb = StringBuilder()
            var p: ViewParent? = v.parent
            var i = 0
            while (p != null && i++ < 12) {
                sb.append(p.javaClass.name)
                if (p.parent != null) sb.append(" <- ")
                p = p.parent
            }
            xlog("scene[$tag] chain: $sb")
            val root = v.rootView
            xlog("scene[$tag] root=${root.javaClass.name}")
        }
    }

    // ------------------------------------------------------------------
    // 推荐页辅助
    // ------------------------------------------------------------------

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

    private fun xlog(msg: String) {
        Log.i("BrowseHook $msg")
        runCatching { XposedBridge.log("PPX BrowseHook: $msg") }
    }

    companion object {
        var viewPager: Any? = null
        var isAuto = true
        private var activeImmersive: Any? = null
        private var currentVideoView: Any? = null
        private var feedRecycler: Any? = null
        private var loggedRecycler = false
        private var sceneLogged = false
        private var lastAdvanceAt = 0L

        private val IMMERSIVE_FRAGMENTS = listOf(
            "com.sup.superb.feedui.view.ImmersiveFeedListFragment",
            "com.sup.superb.feedui.view.CardImmersiveFeedFragment",
            "com.sup.superb.feedui.view.SecondaryImmersiveListFragment",
            "com.sup.superb.feedui.view.CollectionFeedFragment"
        )
    }
}
