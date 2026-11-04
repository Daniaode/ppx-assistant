package com.akari.ppx.xp.hook.misc

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.SwitchHook

/** Hides the bottom-bar welfare entry without depending on its obfuscated class. */
class WelfareEntryHook : SwitchHook("remove_welfare_entry") {
    override fun onHook() {
        mainActivityClass?.hookAfterMethod("onWindowFocusChanged", Boolean::class.java) { param ->
            if (param.args[0] == true) hideWelfare((param.thisObject as Activity).window.decorView)
        }
    }

    private fun hideWelfare(view: View) {
        if (view is TextView && view.text?.toString()?.contains("福利") == true) {
            view.visibility = View.GONE
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) hideWelfare(view.getChildAt(index))
        }
    }
}
