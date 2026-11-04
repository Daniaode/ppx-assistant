@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.detailPagerFragClass
import com.akari.ppx.xp.hook.BaseHook

/**
 * 自动刷视频。
 *
 * 两条链路要分开处理：
 *  1. 推荐页 DetailPagerFragment —— ViewPager 翻页，钩 onPageSelected。
 *  2. 视频页（沉浸式信息流）—— RecyclerView + VerticalImmersiveSnapHelper。
 *
 * 视频页的关键点（0.0.14/0.0.15 失效的真正原因）：
 * 它的翻页回调是 SnapPageScrollListener.a(int)，但基类里那个方法是**空方法**，
 * 真正实现被匿名内部类 ImmersiveFeedListFragment$mPagerScrollListener$1 覆写。
 * 钩基类只会得到一个永远不会被调用的方法，所以必须钩匿名子类。
 *
 * 另外 RecyclerView 不能用 getDeclaredField("f") 取：f 声明在父类
 * BaseFeedFragment 上，子类 declaredFields 里没有。这里统一走
 * BaseFeedFragment.l()（public final ObservableRecyclerView l()）。
 */
class BrowseHook : BaseHook {
    override fun onHook() {
        val isAutoBrowse: Boolean = XPrefs("auto_browse")
        val isDelayHandoff: Boolean = XPrefs("video_delay_handoff")
        val browseFreq: Int = XPrefs<String>("auto_browse_frequency").let {
            it.toIntOrNull() ?: 3000
        }
        val anyEnabled = isAutoBrowse || isDelayHandoff

        // 推荐页 viewPager 捕获：任一功能开启就捕获
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

        // 推荐页：定时切换下一条
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
                    // View.postDelayed(Runnable, long) 第二个参数必须是 Long，
                    // 传 Int 会找不到方法而静默失败。
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
                // k_(int) = onPlayerStateChanged，5 = 播放完成
                if (5 == param.args[0] as Int) {
                    viewPager?.callMethod("postDelayed", object : Runnable {
                        override fun run() {
                            switchToNext()
                        }
                    }, 0L)
                }
            }
        }

        // 视频页（沉浸式信息流）：钩匿名监听器的 a(int) 覆写
        hookImmersive(isAutoBrowse, browseFreq)

        // 视频页：跟踪当前沉浸页 + 播放结束立即切下一条（不做定时）
        if (isDelayHandoff) {
            hookImmersiveTracking()
            hookPlayerCompletion()
        }
    }

    // ------------------------------------------------------------------
    // 沉浸式视频流（视频 Tab）
    // ------------------------------------------------------------------

    private fun hookImmersive(isAutoBrowse: Boolean, browseFreq: Int) {
        IMMERSIVE_LISTENERS.forEach { name ->
            runCatching {
                name.findClass(cl).hookAfterMethod("a", Int::class.javaPrimitiveType) { param ->
                    val pos = param.args[0] as? Int ?: return@hookAfterMethod
                    val frag = outerFragment(param.thisObject) ?: return@hookAfterMethod
                    activeImmersive = frag
                    activeImmersivePos = pos
                    // 视频页不做定时翻页：只在记录位置，切换由「播放完成」触发
                }
            }.onFailure { Log.e("BrowseHook immersive [$name]: $it") }
        }
    }

    /**
     * 播放结束回调。
     *
     * 注意：TTVideoEnginePlayer.onCompletion(boolean) 是 SeekCompletionListener，
     * 拖动进度条就会触发，**不是**播放结束。真正的播放结束是
     * onCompletion(TTVideoEngine)，内部判断 playbackState == 0 并置 isVideoCompleted。
     */
    private fun hookPlayerCompletion() {
        // 1) 沉浸流播放器，实现 ISupPlayerListener，d() 就是播放完成回调
        runCatching {
            "com.sup.android.supvideoview.videoview.SupVideoView".findClass(cl)
                .hookAfterMethod("d") {
                    onPlayFinished()
                }
        }.onFailure { Log.e("BrowseHook SupVideoView.d: $it") }

        // 2) 兜底：TTVideoEnginePlayer 的播放结束
        runCatching {
            val engineClass = "com.ss.ttvideoengine.TTVideoEngine".findClass(cl)
            "com.sup.android.ttvideoplayer.TTVideoEnginePlayer".findClass(cl)
                .hookAfterMethod("onCompletion", engineClass) { _ ->
                    onPlayFinished()
                }
        }.onFailure { Log.e("BrowseHook completion: $it") }
    }

    /** 播放结束后翻到下一条：不依赖位置回调，直接整页滚动。 */
    private fun onPlayFinished() {
        val frag = activeImmersive ?: return
        if (!isFragmentUsable(frag) || !frag.isResumed) return
        val now = System.currentTimeMillis()
        if (now - lastAdvanceAt < 1200L) return
        lastAdvanceAt = now
        val rv = findRecyclerView(frag) ?: return
        if (rv.height <= 0) return
        rv.post { runCatching { rv.smoothScrollBy(0, rv.height) } }
    }

    /** 用生命周期跟踪当前在前的沉浸页，不依赖翻页回调。 */
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
            }.onFailure { Log.e("BrowseHook tracking [$clsName]: $it") }
        }
    }

    private fun isFragmentUsable(frag: Fragment): Boolean = runCatching {
        frag.isAdded && !frag.isDetached && frag.view != null
    }.getOrDefault(false)

    /** 匿名内部类里持有的外部 Fragment 引用（Immersive 的字段名是 b）。 */
    private fun outerFragment(listener: Any): Fragment? = runCatching {
        listener.javaClass.declaredFields.firstNotNullOfOrNull { f ->
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) return@firstNotNullOfOrNull null
            f.isAccessible = true
            f.get(listener) as? Fragment
        }
    }.getOrNull()

    /**
     * 稳健获取列表 RecyclerView：
     * BaseFeedFragment.l() 是 public final，最可靠；
     * 再兜底 getMainRecyclerView / 继承链字段 f / 视图树。
     */
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

    /** 滚到下一条；若 smoothScrollToPosition 没把目标项带进来，退化为整页滚动。 */
    private fun advance(rv: RecyclerView, pos: Int) {
        val count = rv.adapter?.itemCount ?: return
        val target = pos + 1
        if (target <= 0 || target >= count) return
        runCatching { rv.smoothScrollToPosition(target) }
        rv.postDelayed({
            val lm = rv.layoutManager
            if (lm != null && lm.findViewByPosition(target) == null) {
                runCatching { rv.smoothScrollBy(0, rv.height.coerceAtLeast(1)) }
            }
        }, 800L)
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

    companion object {
        var viewPager: Any? = null
        var isAuto = true
        private var activeImmersive: Fragment? = null
        private var activeImmersivePos = -1
        private var lastAdvanceAt = 0L

        /** 各沉浸式信息流 Fragment 的翻页监听匿名类，均覆写了 a(int)。 */
        private val IMMERSIVE_LISTENERS = listOf(
            "com.sup.superb.feedui.view.ImmersiveFeedListFragment\$mPagerScrollListener\$1",
            "com.sup.superb.feedui.view.CardImmersiveFeedFragment\$mPagerScrollListener\$1",
            "com.sup.superb.feedui.view.SecondaryImmersiveListFragment\$mPagerScrollListener\$1",
            "com.sup.superb.feedui.view.CollectionFeedFragment\$mPagerScrollListener\$1"
        )

        /** 沉浸式信息流 Fragment 本体，用于跟踪当前可见页面。 */
        private val IMMERSIVE_FRAGMENTS = listOf(
            "com.sup.superb.feedui.view.ImmersiveFeedListFragment",
            "com.sup.superb.feedui.view.CardImmersiveFeedFragment",
            "com.sup.superb.feedui.view.SecondaryImmersiveListFragment",
            "com.sup.superb.feedui.view.CollectionFeedFragment"
        )
    }
}
