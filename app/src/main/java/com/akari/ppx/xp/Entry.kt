@file:Suppress("DEPRECATION")

package com.akari.ppx.xp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Entry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        ModuleEntryBridge.onLegacyLoadPackage(lpparam.packageName, lpparam.classLoader)
    }
}
