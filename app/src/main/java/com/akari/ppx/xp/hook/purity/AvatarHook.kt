@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class AvatarHook : SwitchHook("remove_avatar_decoration") {
    override fun onHook() {
        // Filter at the RENDER layer only. Blank UserInfo.getDecorationList would
        // make the host see an empty decoration list, refetch it from the server,
        // and loop (header rebinding ~5x/second = visible avatar flicker).
        val avatarViewClass = "com.sup.android.uikit.avatar.FrameAvatarView".findClass(cl)
        val renderMethod = avatarViewClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == List::class.java
        }?.name ?: return
        avatarViewClass.hookBeforeMethod(renderMethod, List::class.java) { param ->
            param.args[0] = emptyList<Any>()
        }
    }
}
