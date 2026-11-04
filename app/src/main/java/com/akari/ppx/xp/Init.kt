@file:Suppress("unused", "unchecked_cast")

package com.akari.ppx.xp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Handler
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.data.Const.CACHE_NAME
import com.akari.ppx.utils.allClassesList
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.Log
import java.io.*
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.reflect.KProperty

@SuppressLint("StaticFieldLeak")
object Init {
    lateinit var cl: ClassLoader

    lateinit var ctx: Context

    private lateinit var cache: MutableMap<String, String?>

    operator fun invoke(context: Context) {
        ctx = context
        cache = readCache(context)
        Log.i("Init cache size=${cache.size}")
        if (checkCache()) {
            writeCache(context)
            Log.i("Init cache rebuilt size=${cache.size}")
        }
        Log.i("Init myTabList=${cache["class_my_tab_list"]}#${cache["method_my_tab_list"]}")
        Log.i("Init myTabView=${cache["class_my_tab_view"]}#${cache["method_my_tab_view"]}")
        Log.i(
            "Init videoSave=${cache["class_video_download_helper"]}#" +
                    "${cache["method_do_download"]}#" +
                    "${cache["class_video_downLoad_config"]}#" +
                    "${cache["method_download_video"]}#" +
                    "${cache["method_has_download_video"]}#" +
                    "${cache["method_enable_download_god_video"]}"
        )
    }

    val safeModeApplicationClass by Weak {
        "com.sup.android.safemode.SafeModeApplication".findClass(cl)
    }

    val mainActivityClass by Weak {
        "com.sup.android.base.MainActivity".findClass(cl)
    }

    val absFeedCellClass by Weak {
        "com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell".findClass(cl)
    }

    val detailPagerFragClass by Weak {
        "com.sup.android.detail.ui.DetailPagerFragment".findClass(cl)
    }

    val videoDownloadHelperClass by Weak {
        cache["class_video_download_helper"]?.findClass(cl)
    }

    val videoDownloadConfigClass by Weak {
        cache["class_video_downLoad_config"]?.findClass(cl)
    }

    val feedCellUtilClass by Weak {
        cache["class_absFeedCellUtil"]?.findClass(cl)
    }

    val feedCellUtilCompanionClass by Weak {
        cache["class_absFeedCellUtil_Companion"]?.findClass(cl)
    }

    val detailViewControllerClass by Weak {
        cache["class_detail_view_controller"]?.findClass(cl)
    }

    val splashAdClass by Weak {
        cache["class_splash_ad"]?.findClass(cl)
    }

    val tabItemsClass by Weak {
        cache["class_tab_items"]?.findClass(cl)
    }

    val myTabListClass by Weak {
        cache["class_my_tab_list"]?.findClass(cl)
    }

    val myTabViewClass by Weak {
        cache["class_my_tab_view"]?.findClass(cl)
    }

    val asyncCallbackClass by Weak {
        cache["class_async_callback"]?.findClass(cl)
    }

    val shareViewClass by Weak {
        cache["class_share_view"]?.findClass(cl)
    }

    val commentResponseClass by Weak {
        cache["class_comment_response"]?.findClass(cl)
    }

    val feedResponseClass by Weak {
        cache["class_feed_response"]?.findClass(cl)
    }

    val historyPosterClass by Weak {
        cache["class_history_poster"]?.findClass(cl)
    }

    val routerClass by Weak {
        cache["class_router"]?.findClass(cl)
    }

    val actionType1Class by Weak {
        cache["class_action_type_1"]?.findClass(cl)
    }

    val actionType2Class by Weak {
        cache["class_action_type_2"]?.findClass(cl)
    }

    val downListenerClass by Weak {
        cache["class_down_listener"]?.findClass(cl)
    }

    val enterPi1Class by Weak {
        cache["class_enter_pi_1"]?.findClass(cl)
    }

    val enterPi2Class by Weak {
        cache["class_enter_pi_2"]?.findClass(cl)
    }

    val profileCondClass by Weak {
        cache["class_profile_cond"]?.findClass(cl)
    }

    val searchHintClass by Weak {
        cache["class_search_hint"]?.findClass(cl)
    }

    val cellDiggerClass by Weak {
        cache["class_cell_digger"]?.findClass(cl)
    }

    val cellDisserClass by Weak {
        cache["class_cell_disser"]?.findClass(cl)
    }

    val godCommentDiggerClass by Weak {
        cache["class_god_comment_digger"]?.findClass(cl)
    }

    val vControllerHandlerClass by Weak {
        cache["class_video_controller_handler"]?.findClass(cl)
    }

    val inexactDateClass by Weak {
        cache["class_inexact_date"]?.findClass(cl)
    }

    val vMotionEventHandlerClass by Weak {
        cache["class_video_motion_event_handler"]?.findClass(cl)
    }

    val commentHolderClass by Weak {
        cache["class_comment_holder"]?.findClass(cl)
    }

    val photoEditorLauncherClass by Weak {
        cache["class_photo_editor_launcher"]?.findClass(cl)
    }

    val photoEditorCallbackClass by Weak {
        cache["class_photo_editor_callback"]?.findClass(cl)
    }

    val photoEditorParamsClass by Weak {
        cache["class_photo_editor_params"]?.findClass(cl)
    }

    val webSharerClass by Weak {
        cache["class_web_sharer"]?.findClass(cl)
    }

    val locationShowerClass by Weak {
        cache["class_location_shower"]?.findClass(cl)
    }

    fun removeDetailBottom() = cache["method_remove_detail_bottom_view"]!!

    fun downloadEntry() = cache["method_do_download"]!!

    fun downloadVideo() = cache["method_download_video"]!!

    fun hasDownloadVideo() = cache["method_has_download_video"]!!

    fun downloadGodVideoGate() = cache["method_enable_download_god_video"]!!

    fun getVideoDownload() = cache["method_get_video_download"]!!

    fun getAuthorInfo() = cache["method_get_userInfo"]!!

    fun canShowActionPi() = cache["method_can_show_action_pi"]!!

    fun tabItems() = cache["method_tab_items"]!!

    fun myTabList() = cache["method_my_tab_list"]!!

    fun myTabView() = cache["method_my_tab_view"]!!

    fun asyncCallback() = cache["method_async_callback"]!!

    fun feedResponse() = cache["method_feed_response"]!!

    fun historyPoster() = cache["method_history_poster"]!!

    fun router() = cache["method_router"]!!

    fun actionType1() = cache["method_action_type_1"]!!

    fun actionType2() = cache["method_action_type_2"]!!

    fun downConfig() = cache["field_down_config"]!!

    fun enterPi1() = cache["method_enter_pi_1"]!!

    fun enterPi2() = cache["method_enter_pi_2"]!!

    fun profileCond() = cache["method_profile_cond"]!!

    fun searchHint() = cache["method_search_hint"]!!

    fun cellDigger() = cache["method_cell_digger"]!!

    fun inexactDate() = cache["method_inexact_date"]!!

    fun vMotionEventHandler() = cache["method_video_motion_event_handler"]!!

    fun commentHolder() = cache["method_comment_holder"]!!

    fun photoEditorLauncher() = cache["method_photo_editor_launcher"]!!

    fun locationShower() = cache["method_location_shower"]!!

    private fun findDetailViewController(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.detail.util.viewcontroller")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.detail.view.DetailBottomView" }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 2 && m.parameterTypes[0] == Int::class.java && m.parameterTypes[1] == Boolean::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun sameType(actual: Class<*>, expected: Class<*>): Boolean {
        if (actual == expected) {
            return true
        }
        val actualBoxed = when (actual) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> actual
        }
        val expectedBoxed = when (expected) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> expected
        }
        return actualBoxed == expectedBoxed
    }

    private fun Method.parametersMatch(vararg expected: Class<*>): Boolean {
        if (parameterTypes.size != expected.size) {
            return false
        }
        return parameterTypes.indices.all { index ->
            sameType(parameterTypes[index], expected[index])
        }
    }

    private fun findVideoSaveClass(): Array<String?> {
        runCatching {
            fun findClassCompat(vararg names: String) = names.firstNotNullOfOrNull { name ->
                runCatching { name.findClass(cl) }.getOrNull()
            }
            fun isBooleanType(name: String) = name == "boolean" || name == "java.lang.Boolean"

            val helper = findClassCompat(
                "com.sup.android.video.g",
                "com.sup.android.video.VideoDownloadHelper"
            ) ?: return arrayOfNulls(6)
            val config = findClassCompat(
                "com.sup.android.video.f",
                "com.sup.android.video.VideoDownLoadConfig"
            ) ?: return arrayOfNulls(6)
            val videoModelClass = "com.sup.android.base.model.VideoModel".findClass(cl)
            val downloadListenerClass =
                "com.ss.android.socialbase.downloader.depend.IDownloadListener".findClass(cl)
            val logCallbackClass = findClassCompat(
                "com.sup.android.video.d",
                "com.sup.android.video.IVideoDownloadLogCallback"
            ) ?: return arrayOfNulls(6)
            val function1Class = "kotlin.jvm.functions.Function1".findClass(cl)
            val methods = helper.declaredMethods
            val downloadEntryMethod = methods.find { m ->
                m.returnType == Void.TYPE && m.parametersMatch(
                    Context::class.java,
                    videoModelClass,
                    config,
                    downloadListenerClass,
                    videoModelClass,
                    java.lang.Boolean.TYPE,
                    function1Class
                )
            }
            val downloadVideo = methods.find { m ->
                m.returnType == Void.TYPE && m.parametersMatch(
                    Activity::class.java,
                    videoModelClass,
                    config,
                    downloadListenerClass,
                    logCallbackClass,
                    videoModelClass,
                    java.lang.Boolean.TYPE,
                    function1Class
                )
            }
            val hasDownloadVideo = methods.find { m ->
                m.parametersMatch(
                    Context::class.java,
                    videoModelClass,
                    String::class.java
                ) && isBooleanType(m.returnType.name)
            }
            val enableDownloadGodVideo = methods.find { m ->
                m.parameterTypes.isEmpty() && isBooleanType(m.returnType.name)
            }
            Log.i(
                "Init findVideoSaveClass helper=${helper.name} config=${config.name} " +
                    "entry=${downloadEntryMethod?.name} final=${downloadVideo?.name} " +
                    "has=${hasDownloadVideo?.name} gate=${enableDownloadGodVideo?.name}"
            )
            if (downloadEntryMethod != null && downloadVideo != null && hasDownloadVideo != null && enableDownloadGodVideo != null) {
                return arrayOf(
                    helper.name,
                    downloadEntryMethod.name,
                    config.name,
                    downloadVideo.name,
                    hasDownloadVideo.name,
                    enableDownloadGodVideo.name
                )
            }
            Log.w("Init findVideoSaveClass incomplete for helper=${helper.name}")
        }
        return arrayOfNulls(6)
    }

    private fun findFeedCellUtil(): Array<String?> {
        runCatching {
            val util = "com.sup.android.mi.feed.repo.utils.b".findClass(cl)
            val companion = "com.sup.android.mi.feed.repo.utils.b\$a".findClass(cl)
            val getVideoDownload = companion.declaredMethods.find { m ->
                m.name == "P" && m.parameterTypes.size == 1 && m.parameterTypes[0] == absFeedCellClass
                        && m.returnType.name == "com.sup.android.base.model.VideoModel"
            }
            val getAuthorInfo = companion.declaredMethods.find { m ->
                m.name == "D" && m.parameterTypes.size == 1 && m.parameterTypes[0] == absFeedCellClass
                        && m.returnType.name == "com.sup.android.mi.usercenter.model.UserInfo"
            }
            val canShowActionPi = companion.declaredMethods.find { m ->
                m.name == "ay" && m.parameterTypes.size == 1 && m.parameterTypes[0] == absFeedCellClass
                        && sameType(m.returnType, java.lang.Boolean.TYPE)
            }
            if (getVideoDownload != null && getAuthorInfo != null && canShowActionPi != null) {
                return arrayOf(
                    util.name,
                    companion.name,
                    getVideoDownload.name,
                    getAuthorInfo.name,
                    canShowActionPi.name
                )
            }
        }
        return arrayOfNulls(5)
    }

    private fun findSplashAd(): String? =
        classesList.filter {
            it.startsWith("com.sup.android.superb.m_ad.initializer")
        }.find { c ->
            c.findClass(cl).declaredFields.any { it.type.name == "java.util.concurrent.CopyOnWriteArraySet" }
        }

    private fun findTabItems(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.m_mine.view.subview")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.mi.usercenter.IUserCenterService" }
                    && !c.declaredFields.any { it.type.name == "com.sup.android.m_mine.bean.MyTabItem" }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == ArrayList::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findMyTabList(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.m_mine.utils")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type == Context::class.java }
                    && c.declaredFields.any { it.type == String::class.java }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.isEmpty() && m.returnType == ArrayList::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findMyTabView(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.m_mine.view.subview")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type == LinearLayout::class.java }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == c && m.returnType.name == "com.sup.android.m_mine.bean.MyTabItem")
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findAsyncCallback(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.mi.usercenter")
        }.map {
            it.findClass(cl)
        }.filter { c ->
            c.declaredFields.isEmpty()
        }.forEach { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0].name == "com.sup.android.business_utils.network.ModelResult")
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findShareView(): String? =
        "com.sup.android.m_sharecontroller.ui.e".takeIf { name ->
            runCatching {
                name.findClass(cl).run {
                    declaredFields.any { it.type.name == "[Lcom.sup.android.i_sharecontroller.model.c;" }
                            && declaredFields.any { it.type.name == "[Lcom.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType;" }
                            && declaredFields.any { it.type.name == "com.sup.android.i_sharecontroller.model.OptionAction\$a" }
                            && declaredConstructors.any { constructor ->
                        constructor.parameterTypes.map { it.name } == listOf(
                            "android.content.Context",
                            "[Lcom.sup.android.i_sharecontroller.model.c;",
                            "[Lcom.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType;",
                            "com.sup.android.i_sharecontroller.model.OptionAction\$a",
                            "com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell"
                        )
                    }
                }
            }.getOrDefault(false)
        }

    private fun findCommentResponse(): String? =
        classesList.filter {
            it.startsWith("com.sup.android.mi.feed.repo.response")
        }.find { c ->
            c.findClass(cl).declaredFields.any { it.type.name == "com.sup.android.mi.feed.repo.bean.comment.CommentCursor" }
        }

    private fun findFeedResponse(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.module.feed.repo.manager")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "java.util.concurrent.CountDownLatch" }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 4 && m.parameterTypes[0] == String::class.java && m.parameterTypes[1].name == "com.sup.android.mi.feed.repo.bean.FeedResponse"
                    && sameType(m.parameterTypes[2], java.lang.Boolean.TYPE) && sameType(m.parameterTypes[3], Int::class.java)
                )
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findHistoryPoster(): Array<String?> {
        "com.sup.superb.feedui.repo.a".findClass(cl).let { c ->
            c.declaredMethods.find { m ->
                m.name == "a" && m.parameterTypes.size == 1
                        && m.parameterTypes[0] == List::class.java && m.returnType == Boolean::class.java
            }?.let { return arrayOf(c.name, it.name) }
        }
        return arrayOfNulls(2)
    }

    private fun findRouter(): Array<String?> {
        classesList.filter {
            it.startsWith("com.bytedance.router")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type == java.util.List::class.java }
                    && c.declaredFields.any { it.type == Context::class.java }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 2 && m.parameterTypes[0] == Context::class.java
                    && m.parameterTypes[1].name == "com.bytedance.router.RouteIntent" && m.returnType.name == "void"
                )
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findActionType1(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.superb.feedui.docker.part")
        }.map {
            it.findClass(cl)
        }.forEach { c ->
            c.declaredMethods.find { m ->
                m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == absFeedCellClass &&
                    m.returnType.name == "[Lcom.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType;"
            }?.let { return arrayOf(c.name, it.name) }
        }
        return arrayOfNulls(2)
    }

    private fun findActionType2(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.detail.util.viewcontroller")
        }.map {
            it.findClass(cl)
        }.forEach { c ->
            c.declaredMethods.find { m ->
                m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == absFeedCellClass &&
                    sameType(m.parameterTypes[1], java.lang.Boolean.TYPE) &&
                    m.returnType.name == "[Lcom.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType;"
            }?.let { return arrayOf(c.name, it.name) }
        }
        return arrayOfNulls(2)
    }

    private fun findDownloadListener(): Array<String?> {
        "com.sup.android.video.g\$b".findClass(cl).let { c ->
            c.declaredFields.find { f ->
                f.name == "f" && f.type.name == "com.sup.android.video.f"
            }?.let { return arrayOf(c.name, it.name) }
        }
        return arrayOfNulls(2)
    }

    private fun findEnterPi1(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.base.feed")
        }.map {
            it.findClass(cl)
        }.forEach { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 6
                    && m.parameterTypes[0] == Activity::class.java && m.parameterTypes[1] == absFeedCellClass
                    && m.parameterTypes[2] == String::class.java && m.parameterTypes[3] == String::class.java
                    && m.parameterTypes[4] == String::class.java && m.parameterTypes[5] == Boolean::class.java
                )
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findEnterPi2(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.base")
        }.map {
            it.findClass(cl)
        }.forEach { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 7 && m.parameterTypes[0] == Activity::class.java
                    && m.parameterTypes[1] == absFeedCellClass && m.parameterTypes[2] == String::class.java
                    && m.parameterTypes[3] == String::class.java && m.parameterTypes[4] == String::class.java
                    && m.parameterTypes[5] == java.util.HashMap::class.java && m.parameterTypes[6] == Boolean::class.java
                )
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findProfileCond(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.module.profile")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.mi.usercenter.IUserCenterService" }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Long::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findSearchHint(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.module.profile.search")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type == String::class.java }
                    && c.declaredFields.any { it.type.name == "androidx.fragment.app.Fragment" }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findCellDigger(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.detail.util")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.mi.feed.repo.IFeedCellService" }
                    && c.declaredFields.any { it.type.name == "com.sup.android.mi.usercenter.IUserCenterService" }
                    && c.declaredFields.any { it.type == Handler::class.java }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 5 && m.parameterTypes[0] == Int::class.java
                    && m.parameterTypes[1] == Long::class.java && m.parameterTypes[2] == Boolean::class.java
                    && m.parameterTypes[3] == Int::class.java && m.parameterTypes[4] == Int::class.java
                )
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findCellDisser(): String? =
        classesList.filter {
            it.startsWith("com.sup.superb.m_feedui_common.util")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.mi.feed.repo.IFeedCellService" }
                    && c.declaredFields.any { it.type.name == "com.sup.android.mi.usercenter.IUserCenterService" }
                    && c.declaredFields.any { it.type.name == "com.sup.android.mi.feed.repo.IFeedListService" }
        }?.name

    private fun findGodCommentDigger(): String? =
        classesList.filter {
            it.startsWith("com.sup.android.m_comment.util.helper")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.mi.feed.repo.IFeedCellService" }
                    && c.declaredFields.any { it.type.name == "com.sup.android.mi.usercenter.IUserCenterService" }
        }?.name

    private fun findVControllerHandler(): String? =
        classesList.filter {
            it.startsWith("com.sup.superb.video.controllerlayer")
        }.find { c ->
            c.findClass(cl).declaredFields.any { it.type == ProgressBar::class.java }
                    && c.findClass(cl).declaredFields.count { it.type == ImageView::class.java } == 2
                    && c.findClass(cl).declaredFields.count { it.type == TextView::class.java } == 2
        }

    private fun findInexactDate(): Array<String?> {
        "com.sup.superb.m_feedui_common.util.a".findClass(cl).let { c ->
            c.declaredMethods.find { m ->
                m.name == "a" && m.parameterTypes.size == 2 && m.parameterTypes[0] == Long::class.java
                        && m.parameterTypes[1].name == "kotlin.jvm.functions.Function0" && m.returnType == String::class.java
            }?.let { return arrayOf(c.name, it.name) }
        }
        return arrayOfNulls(2)
    }

    private fun findVMotionEventHandler(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.superb.video.viewholder")
        }.map {
            it.findClass(cl)
        }.forEach { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == MotionEvent::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findCommentHolder(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.m_comment.docker.holder")
        }.map {
            it.findClass(cl)
        }.find { c ->
            c.declaredFields.any { it.type.name == "com.airbnb.lottie.LottieAnimationView" }
                    && c.declaredFields.any { it.type == absFeedCellClass }
        }?.let { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == c && m.returnType == absFeedCellClass)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun findPhotoEditorLauncher(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.superb.video")
        }.map {
            it.findClass(cl)
        }.filter { c ->
            c.declaredFields.any { it.type == c }
                    && c.declaredFields.any { it.type == Boolean::class.java }
        }.forEach { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.size == 4 && m.parameterTypes[0] == Activity::class.java
                    && m.parameterTypes[1] == String::class.java && m.parameterTypes[2].name.startsWith(
                        "com.sup.android.i_photoeditorui"
                    )
                    && m.parameterTypes[3].name.startsWith("com.sup.android.i_photoeditorui") && m.returnType.name == "void"
                )
                    return arrayOf(
                        c.name,
                        m.parameterTypes[2].name,
                        m.parameterTypes[3].name,
                        m.name
                    )
            }
        }
        return arrayOfNulls(4)
    }

    private fun findWebSharer(): String? =
        classesList.filter {
            it.startsWith("com.sup.android.m_web.bridge")
        }.find { c ->
            c.findClass(cl).declaredFields.any { it.type == Boolean::class.java }
                    && c.findClass(cl).declaredFields.any { it.type == Handler::class.java }
                    && c.findClass(cl).declaredFields.any { it.type.name == "com.sup.android.mi.usercenter.IUserCenterService" }
        }

    private fun findLocationShower(): Array<String?> {
        classesList.filter {
            it.startsWith("com.sup.android.module.publish.view")
        }.map {
            it.findClass(cl)
        }.filter { c ->
            c.declaredFields.any { it.type.name == "com.sup.android.module.publish.view.PublishLocationLabelAdapter" }
                    && c.declaredFields.any { it.type.name == "com.sup.android.mi.feed.repo.bean.option.POIData" }
        }.forEach { c ->
            c.declaredMethods.forEach { m ->
                if (m.parameterTypes.isEmpty() && m.returnType == Boolean::class.java)
                    return arrayOf(c.name, m.name)
            }
        }
        return arrayOfNulls(2)
    }

    private fun checkCache(): Boolean {
        var needUpdate = false

        fun <K, V> MutableMap<K, V>.checkOrPut(
            key: K,
            value: () -> V
        ): MutableMap<K, V> {
            if (!containsKey(key) || get(key) == null) {
                put(key, value())
                needUpdate = true
            }
            return this
        }

        fun <K, V> MutableMap<K, V>.checkOrPut(
            vararg keys: K,
            values: () -> Array<V>
        ): MutableMap<K, V> {
            if (!keys.fold(true) { acc, key -> acc && containsKey(key) && get(key) != null }) {
                putAll(keys.zip(values()))
                needUpdate = true
            }
            return this
        }

        cache.checkOrPut(
            "class_video_download_helper",
            "method_do_download",
            "class_video_downLoad_config",
            "method_download_video",
            "method_has_download_video",
            "method_enable_download_god_video",
            values = ::findVideoSaveClass
        )
            .checkOrPut(
                "class_absFeedCellUtil",
                "class_absFeedCellUtil_Companion",
                "method_get_video_download",
                "method_get_userInfo",
                "method_can_show_action_pi",
                values = ::findFeedCellUtil
            )
            .checkOrPut(
            "class_detail_view_controller",
            "method_remove_detail_bottom_view",
            values = ::findDetailViewController
        )
            .checkOrPut("class_splash_ad", value = ::findSplashAd)
            .checkOrPut("class_tab_items", "method_tab_items", values = ::findTabItems)
            .checkOrPut("class_my_tab_list", "method_my_tab_list", values = ::findMyTabList)
            .checkOrPut("class_my_tab_view", "method_my_tab_view", values = ::findMyTabView)
            .checkOrPut(
                "class_async_callback",
                "method_async_callback",
                values = ::findAsyncCallback
            )
            .checkOrPut("class_share_view", value = ::findShareView)
            .checkOrPut("class_comment_response", value = ::findCommentResponse)
            .checkOrPut("class_feed_response", "method_feed_response", values = ::findFeedResponse)
            .checkOrPut(
                "class_history_poster",
                "method_history_poster",
                values = ::findHistoryPoster
            )
            .checkOrPut("class_router", "method_router", values = ::findRouter)
            .checkOrPut("class_action_type_1", "method_action_type_1", values = ::findActionType1)
            .checkOrPut("class_action_type_2", "method_action_type_2", values = ::findActionType2)
            .checkOrPut("class_down_listener", "field_down_config", values = ::findDownloadListener)
            .checkOrPut("class_enter_pi_1", "method_enter_pi_1", values = ::findEnterPi1)
            .checkOrPut("class_enter_pi_2", "method_enter_pi_2", values = ::findEnterPi2)
            .checkOrPut("class_profile_cond", "method_profile_cond", values = ::findProfileCond)
            .checkOrPut("class_search_hint", "method_search_hint", values = ::findSearchHint)
            .checkOrPut("class_cell_digger", "method_cell_digger", values = ::findCellDigger)
            .checkOrPut("class_cell_disser", value = ::findCellDisser)
            .checkOrPut("class_god_comment_digger", value = ::findGodCommentDigger)
            .checkOrPut("class_video_controller_handler", value = ::findVControllerHandler)
            .checkOrPut("class_inexact_date", "method_inexact_date", values = ::findInexactDate)
            .checkOrPut(
                "class_video_motion_event_handler",
                "method_video_motion_event_handler",
                values = ::findVMotionEventHandler
            )
            .checkOrPut(
                "class_comment_holder",
                "method_comment_holder",
                values = ::findCommentHolder
            )
            .checkOrPut(
                "class_photo_editor_launcher",
                "class_photo_editor_callback",
                "class_photo_editor_params",
                "method_photo_editor_launcher",
                values = ::findPhotoEditorLauncher
            )
            .checkOrPut("class_web_sharer", value = ::findWebSharer)
            .checkOrPut(
                "class_location_shower",
                "method_location_shower",
                values = ::findLocationShower
            )

        return needUpdate
    }

    private val classesList by lazy {
        cl.allClassesList().asSequence()
    }

    private fun packageLastUpdateTime(context: Context, packageName: String): Long {
        val packageInfo: PackageInfo? = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (e: Throwable) {
            null
        }
        return packageInfo?.lastUpdateTime ?: 0L
    }

    private fun readCache(context: Context): MutableMap<String, String?> {
        try {
            val hookCache = File(context.cacheDir, CACHE_NAME)
            if (hookCache.isFile && hookCache.canRead()) {
                val lastUpdateTime = packageLastUpdateTime(context, context.packageName)
                val lastModuleUpdateTime = packageLastUpdateTime(context, APPLICATION_ID)
                val stream = ObjectInputStream(FileInputStream(hookCache))
                val lastHookInfoUpdateTime = stream.readLong()
                if (lastHookInfoUpdateTime >= lastUpdateTime && lastHookInfoUpdateTime >= lastModuleUpdateTime)
                    return stream.readObject() as MutableMap<String, String?>
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return HashMap()
    }

    private fun writeCache(context: Context) {
        try {
            val hookCache = File(context.cacheDir, CACHE_NAME)
            val lastUpdateTime = packageLastUpdateTime(context, context.packageName)
            val lastModuleUpdateTime = packageLastUpdateTime(context, APPLICATION_ID)
            if (hookCache.exists()) {
                hookCache.delete()
            }
            ObjectOutputStream(FileOutputStream(hookCache)).use { stream ->
                stream.writeLong(max(lastModuleUpdateTime, lastUpdateTime))
                stream.writeObject(cache)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    class Weak(val initializer: () -> Class<*>?) {
        private var weakReference: WeakReference<Class<*>?>? = null
        operator fun getValue(thisRef: Any?, property: KProperty<*>) = weakReference?.get() ?: let {
            weakReference = WeakReference(initializer())
            weakReference?.get()
        }

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Class<*>) {
            weakReference = WeakReference(value)
        }
    }
}
