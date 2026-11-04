@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.view.View
import com.akari.ppx.data.Const.APP_NAME
import com.akari.ppx.data.Const.TAB_SCHEMA
import com.akari.ppx.ui.MainActivity
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethod
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.callStaticMethodOrNull
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.getObjectFieldOrNull
import com.akari.ppx.utils.getObjectFieldOrNullAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.new
import com.akari.ppx.utils.replaceMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.myTabList
import com.akari.ppx.xp.Init.myTabListClass
import com.akari.ppx.xp.Init.myTabView
import com.akari.ppx.xp.Init.myTabViewClass
import com.akari.ppx.xp.hook.BaseHook

class InnerOpenHook : BaseHook {
    override fun onHook() {
        runCatching {
            val tabListClass = myTabListClass ?: return@runCatching
            val tabListMethod = myTabList()
            val myTabItemClass = "com.sup.android.m_mine.bean.MyTabItem".findClass(cl)
            Log.i("InnerOpenHook tabList=${tabListClass.name}#$tabListMethod")

            tabListClass.hookAfterMethod(tabListMethod) { param ->
                val items = param.result as? ArrayList<Any?> ?: return@hookAfterMethod
                val beforeSize = items.size
                if (items.any { it?.callMethodOrNullAs<String>("getTabSchema") == TAB_SCHEMA }) {
                    Log.d("InnerOpenHook entry exists size=$beforeSize")
                    return@hookAfterMethod
                }

                items.add(
                    myTabItemClass.new().apply {
                        callMethod("setSchemaNeedLogin", false)
                        callMethod("setTabName", APP_NAME)
                        callMethod("setTabSchema", TAB_SCHEMA)
                        callMethod("setExtra", "{\"icon_list\":null,\"alert\":false}")
                        callMethod("setType", 4)
                    }
                )
                Log.i("InnerOpenHook entry inserted size=$beforeSize->${items.size}")
            }
        }.onFailure(Log::e)

        runCatching {
            val tabViewClass = myTabViewClass ?: return@runCatching
            val tabViewMethod = myTabView()
            val viewFieldName = tabViewClass.declaredFields.firstOrNull { it.type == View::class.java }?.name
            Log.i("InnerOpenHook tabView=${tabViewClass.name}#$tabViewMethod")

            "${tabViewClass.name}\$1".replaceMethod(cl, "doClick", View::class.java) { param ->
                val tabView = param.thisObject.getObjectFieldOrNull("b")
                    ?: return@replaceMethod param.invokeOriginalMethod()
                val tab = tabViewClass.callStaticMethodOrNull(tabViewMethod, tabView)
                if (tab?.callMethodOrNullAs<String>("getTabSchema") != TAB_SCHEMA) {
                    return@replaceMethod param.invokeOriginalMethod()
                }

                val context = viewFieldName?.let { field ->
                    tabView.getObjectFieldOrNullAs<View>(field)?.context
                } ?: return@replaceMethod param.invokeOriginalMethod()

                Log.i("InnerOpenHook open settings")
                MainActivity(context)
                null
            }
        }.onFailure(Log::e)
    }
}
