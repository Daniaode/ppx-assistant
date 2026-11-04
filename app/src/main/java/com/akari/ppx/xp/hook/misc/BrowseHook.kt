@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.detailPagerFragClass
import com.akari.ppx.xp.hook.BaseHook

/**
 * 自动刷视频：定时切换下一条 + 视频播放完再切换。
 *
 * 6.6.1 中 DetailPagerFragment 存在多个 ArrayList 字段（cachedCells 与 r），
 * 旧代码 `find { f.type == ArrayList::class.java }` 会取错字段导致 feedCell 为 null，
 * 提前 return 而不切换。这里改为：优先取泛型含 AbsFeedCell 的字段，兜底遍历全部
 * ArrayList 字段，并保证"定时切换"不依赖 feedCell（仅"播完再切"才需要判断帖子类型）。
 */
class BrowseHook : BaseHook {
    override fun onHook() {
        val isAutoBrowse: Boolean = XPrefs("auto_browse")
        val isDelayHandoff: Boolean = XPrefs("video_delay_handoff")
        val browseFreq: Int = XPrefs<String>("auto_browse_frequency").let {
            it.toIntOrNull() ?: 3000
        }

        // viewPager 捕获：自动刷视频/播完再切任一开启就捕获，否则单独开"播完再切"时 viewPager 为 null
        (isAutoBrowse || isDelayHandoff).check(true) {
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

        isAutoBrowse.check(true) {
            // 定时自动切换下一条
            detailPagerFragClass?.hookAfterMethod("onPageSelected", Int::class.java) { param ->
                if (!isAuto) {
                    isAuto = true
                    return@hookAfterMethod
                }
                // 每次页面切换都从实例直接刷新 viewPager（不依赖 onCreateView 捕获时机）
                refreshViewPager(param.thisObject)
                val position = param.args[0] as Int
                val shouldSwitch = if (isDelayHandoff) {
                    // 播完再切：仅对"图文/笔记"类帖子按定时切换；视频交给播放完成回调
                    val feedCell = findFeedCell(param.thisObject, position)
                    feedCell != null && "com.sup.android.mi.feed.repo.bean.cell.NoteFeedItem"
                        .findClass(cl)
                        .isInstance(feedCell.callMethodOrNull("getFeedItem"))
                } else {
                    true
                }
                if (shouldSwitch) {
                    // 关键：View.postDelayed(Runnable, long) 第二个参数必须是 Long，
                    // 传 Int 会导致 findMethod 找不到方法而静默失败（长期不生效的根因）
                    viewPager?.callMethod("postDelayed", object : Runnable {
                        override fun run() {
                            switchToNext()
                        }
                    }, (if (1 == position) 1000 else browseFreq).toLong())
                }
            }
        }

        // 视频播放完再切换
        isDelayHandoff.check(true) {
            val videoHolderClass = "com.sup.superb.video.viewholder.a".findClass(cl)
            videoHolderClass.hookAfterMethod("k_", Int::class.java) { param ->
                // k_(int) = onPlayerStateChanged，5 = 播放完成；不再依赖 getCellType（该值不一定是 1）
                if (5 == param.args[0] as Int) {
                    viewPager?.callMethod("postDelayed", object : Runnable {
                        override fun run() {
                            switchToNext()
                        }
                    }, 0L)
                }
            }
        }

        // 「视频」页：ImmersiveFeedListFragment 用 RecyclerView 纵向翻页，
        // 翻页回调 SnapPageScrollListener.a(int)，翻页时滚动 RecyclerView 到下一条。
        isAutoBrowse.check(true) {
            runCatching {
                "com.sup.superb.feedui.view.SnapPageScrollListener".findClass(cl)
                    .hookAfterMethod("a", Int::class.javaPrimitiveType) { param ->
                        if (!isAuto) {
                            isAuto = true
                            return@hookAfterMethod
                        }
                        val pos = param.args[0] as Int
                        val listener = param.thisObject
                        // 匿名内部类字段里找外部 Fragment 引用
                        val frag = runCatching {
                            listener.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                                f.isAccessible = true
                                f.get(listener) as? androidx.fragment.app.Fragment
                            }
                        }.getOrNull() ?: return@hookAfterMethod
                        if (!frag.javaClass.name.contains("ImmersiveFeedListFragment")) return@hookAfterMethod
                        // RecyclerView 是 BaseFeedFragment 的字段 f (mainRecyclerView)，
                        // l() 取不到（不存在/不可见），这才是之前没生效的原因
                        val rv = (frag.getObjectFieldOrNull("f")
                            ?: frag.callMethodOrNull("getMainRecyclerView")) as? androidx.recyclerview.widget.RecyclerView
                            ?: return@hookAfterMethod
                        rv.postDelayed(object : Runnable {
                            override fun run() {
                                runCatching { rv.smoothScrollToPosition(pos + 1) }
                            }
                        }, browseFreq.toLong())
                    }
            }.onFailure { Log.e("BrowseHook immersive: $it") }
        }
    }

    /** 从 Fragment 实例直接找到 SwipeControlledViewPager 字段并刷新全局引用。 */
    private fun refreshViewPager(owner: Any) {
        runCatching {
            owner.javaClass.declaredFields.firstOrNull { f ->
                f.type.name == "com.bytedance.ies.uikit.viewpager.SwipeControlledViewPager"
            }?.apply { isAccessible = true }?.get(owner)?.let { viewPager = it }
        }
    }

    private fun findFeedCell(owner: Any, index: Int): Any? {
        val fields = owner.javaClass.declaredFields.filter { it.type == ArrayList::class.java }
        // 优先取泛型含 AbsFeedCell 的字段
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
    }
}
