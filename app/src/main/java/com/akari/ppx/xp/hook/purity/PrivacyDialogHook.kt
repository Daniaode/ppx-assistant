package com.akari.ppx.xp.hook.purity

import com.akari.ppx.utils.Log
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.SwitchHook

/** Hides the host's cold-launch personal-information guidance dialog. */
class PrivacyDialogHook : SwitchHook("remove_privacy_dialog") {
    override fun onHook() {
        val activity = mainActivityClass ?: run {
            Log.d("PrivacyDialogHook MainActivity not found")
            return
        }
        activity.hookBeforeMethod("registerPrivacyDialog") { param ->
            // The method only registers the dialog in ColdBootDialogManager.
            param.result = null
        }
        Log.i("PrivacyDialogHook hooked ${activity.name}#registerPrivacyDialog")
    }
}
