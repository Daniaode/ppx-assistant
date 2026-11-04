package com.akari.ppx.xp.hook.purity

import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.Log
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook

/**
 * The host shows a "not an official package" uninstall dialog when a request
 * returns result code 11303. Suppressing its UI does not turn an error response
 * into successful data or repair a rejected installation.
 */
class IntegrityDialogHook : BaseHook {
    override fun onHook() {
        val checker = resultCodeCheckerClass() ?: run {
            error("IntegrityDialogHook checker class not found")
        }
        val target = checker.declaredMethods.singleOrNull { method ->
            method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                    && method.returnType == Boolean::class.javaPrimitiveType
        } ?: run {
            error("IntegrityDialogHook code handler missing or ambiguous on ${checker.name}")
        }
        val handle = target.hookBeforeMethod { param ->
            if (param.args[0] == NON_OFFICIAL_PACKAGE_CODE) {
                Log.i("IntegrityDialogHook suppressed result code $NON_OFFICIAL_PACKAGE_CODE dialog")
                param.result = true
            }
        }
        check(handle != null) { "IntegrityDialogHook hook registration failed" }
        Log.i("IntegrityDialogHook hooked ${checker.name}#${target.name}")
    }

    private fun resultCodeCheckerClass(): Class<*>? = runCatching {
        Class.forName(CHECKER_CALLBACK_CLASS, false, cl)
            .declaredFields.first { it.name == "this\$0" }.type
    }.getOrNull()

    private companion object {
        const val NON_OFFICIAL_PACKAGE_CODE = 11303
        const val CHECKER_CALLBACK_CLASS =
            "com.sup.android.base.network.NetworkResultCodeChecker\$showCode3Dialog\$1"
    }
}
