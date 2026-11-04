package com.akari.ppx.utils

import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal enum class HookKind {
    BEFORE,
    AFTER,
    REPLACE
}

class HookParam internal constructor(
    val member: Member,
    thisObject: Any?,
    val args: Array<Any?>,
    private val outcome: HookOutcome,
    private val originalInvoker: (Array<Any?>) -> Any?
) {
    private val rawThisObject = thisObject

    val method: Member
        get() = member

    val thisObject: Any
        get() = rawThisObject!!

    val thisObjectOrNull: Any?
        get() = rawThisObject

    var result: Any?
        get() = outcome.result
        set(value) {
            outcome.setResult(value)
        }

    var throwable: Throwable?
        get() = outcome.throwable
        set(value) {
            outcome.setThrowable(value)
        }

    fun invokeOriginalMethod(): Any? = originalInvoker(args)
}

internal class HookOutcome(
    hasOutcome: Boolean = false,
    initialResult: Any? = null,
    initialThrowable: Throwable? = null
) {
    var hasOutcome = hasOutcome
        private set

    var result: Any? = initialResult
        private set

    var throwable: Throwable? = initialThrowable
        private set

    fun setResult(value: Any?) {
        hasOutcome = true
        throwable = null
        result = value
    }

    fun setThrowable(value: Throwable?) {
        hasOutcome = value != null
        throwable = value
        if (value != null) {
            result = null
        }
    }
}

internal interface HookBackend {
    fun hook(member: Member, kind: HookKind, callback: (HookParam) -> Any?): Any?

    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String?,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any>

    fun hookAllConstructors(
        clazz: Class<*>,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any>
}

object HookRuntime {
    @Volatile
    private var backend: HookBackend = LegacyHookBackend

    @Volatile
    private var remotePreferences: SharedPreferences? = null

    fun useLegacy() {
        backend = LegacyHookBackend
        remotePreferences = null
    }

    fun useModern(module: Any, apiVersion: Int) {
        require(apiVersion >= 101) { "Modern runtime requires libxposed API 101+, got $apiVersion" }
        backend = ModernHookBackend(module)
        remotePreferences = runCatching {
            module.callMethod("getRemotePreferences", "settings") as? SharedPreferences
        }.onFailure {
            Log.e("HookRuntime getRemotePreferences failed")
            Log.e(it)
        }.getOrNull()
    }

    internal fun backend(): HookBackend = backend

    fun remotePreferences(): SharedPreferences? = remotePreferences
}

private object LegacyHookBackend : HookBackend {
    override fun hook(member: Member, kind: HookKind, callback: (HookParam) -> Any?) = try {
        when (kind) {
            HookKind.BEFORE -> XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val outcome = HookOutcome()
                    val bridge = HookParam(
                        member = member,
                        thisObject = param.thisObject,
                        args = param.args as Array<Any?>,
                        outcome = outcome,
                        originalInvoker = { args ->
                            XposedBridge.invokeOriginalMethod(member, param.thisObject, args)
                        }
                    )
                    callback(bridge)
                    if (outcome.hasOutcome) {
                        outcome.throwable?.let {
                            param.throwable = it
                        } ?: run {
                            param.result = outcome.result
                        }
                    }
                }
            })
            HookKind.AFTER -> XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val outcome = HookOutcome(
                        hasOutcome = true,
                        initialResult = param.result,
                        initialThrowable = param.throwable
                    )
                    val bridge = HookParam(
                        member = member,
                        thisObject = param.thisObject,
                        args = param.args as Array<Any?>,
                        outcome = outcome,
                        originalInvoker = { args ->
                            XposedBridge.invokeOriginalMethod(member, param.thisObject, args)
                        }
                    )
                    callback(bridge)
                    outcome.throwable?.let {
                        param.throwable = it
                    } ?: run {
                        param.result = outcome.result
                    }
                }
            })
            HookKind.REPLACE -> XposedBridge.hookMethod(member, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val outcome = HookOutcome()
                    val bridge = HookParam(
                        member = member,
                        thisObject = param.thisObject,
                        args = param.args as Array<Any?>,
                        outcome = outcome,
                        originalInvoker = { args ->
                            XposedBridge.invokeOriginalMethod(member, param.thisObject, args)
                        }
                    )
                    val replaced = callback(bridge)
                    outcome.throwable?.let { throw it }
                    return if (outcome.hasOutcome) outcome.result else replaced
                }
            })
        }
    } catch (e: Throwable) {
        Log.e(e)
        null
    }

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String?,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = runCatching {
        XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.BEFORE) return
                val outcome = HookOutcome()
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                if (outcome.hasOutcome) {
                    outcome.throwable?.let {
                        param.throwable = it
                    } ?: run {
                        param.result = outcome.result
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.AFTER) return
                val outcome = HookOutcome(
                    hasOutcome = true,
                    initialResult = param.result,
                    initialThrowable = param.throwable
                )
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                outcome.throwable?.let {
                    param.throwable = it
                } ?: run {
                    param.result = outcome.result
                }
            }
        }).mapTo(linkedSetOf()) { it as Any }
    }.getOrElse {
        Log.e(it)
        emptySet()
    }

    override fun hookAllConstructors(
        clazz: Class<*>,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = runCatching {
        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.BEFORE) return
                val outcome = HookOutcome()
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                if (outcome.hasOutcome) {
                    outcome.throwable?.let {
                        param.throwable = it
                    } ?: run {
                        param.result = outcome.result
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.AFTER) return
                val outcome = HookOutcome(
                    hasOutcome = true,
                    initialResult = param.result,
                    initialThrowable = param.throwable
                )
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                outcome.throwable?.let {
                    param.throwable = it
                } ?: run {
                    param.result = outcome.result
                }
            }
        }).mapTo(linkedSetOf()) { it as Any }
    }.getOrElse {
        Log.e(it)
        emptySet()
    }
}

private class ModernHookBackend(
    private val module: Any
) : HookBackend {
    override fun hook(member: Member, kind: HookKind, callback: (HookParam) -> Any?) = runCatching {
        hookApi101(member, kind, callback)
    }.onFailure(Log::e).getOrNull()

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String?,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = clazz.declaredMethods
        .filter { methodName == null || it.name == methodName }
        .mapNotNullTo(linkedSetOf()) { hook(it, kind, callback) }

    override fun hookAllConstructors(
        clazz: Class<*>,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = clazz.declaredConstructors
        .mapNotNullTo(linkedSetOf()) { hook(it, kind, callback) }

    private fun hookApi101(member: Member, kind: HookKind, callback: (HookParam) -> Any?): Any? {
        // Class literals, not names: LSPosed's API call protection renames libxposed classes
        // and only rewrites type references in the module dex, never string constants.
        val hookerClass = XposedInterface.Hooker::class.java
        val proxy = Proxy.newProxyInstance(
            hookerClass.classLoader,
            arrayOf(hookerClass)
        ) { _, method, args ->
            when (method.name) {
                "intercept" -> interceptApi101(member, kind, callback, args?.firstOrNull())
                else -> defaultProxyValue(method.returnType)
            }
        }
        val builder = module.callMethod("hook", member) ?: return null
        return builder.callMethod("intercept", proxy)
    }

    private fun interceptApi101(
        member: Member,
        kind: HookKind,
        callback: (HookParam) -> Any?,
        chainObj: Any?
    ): Any? {
        chainObj ?: return null
        val thisObject = chainObj.callMethodOrNull("getThisObject")
        val currentArgs = chainArgs(chainObj)
        val origin = { args: Array<Any?> -> invokeOrigin(member, thisObject, args) }
        return when (kind) {
            HookKind.BEFORE -> {
                val outcome = HookOutcome()
                val bridge = HookParam(member, thisObject, currentArgs.copyOf(), outcome, origin)
                callback(bridge)
                outcome.throwable?.let { throw it }
                if (outcome.hasOutcome) {
                    outcome.result
                } else {
                    proceedApi101(chainObj, bridge.args)
                }
            }
            HookKind.AFTER -> {
                var result: Any? = null
                var error: Throwable? = null
                try {
                    result = proceedApi101(chainObj, currentArgs)
                } catch (t: Throwable) {
                    error = t
                }
                val outcome = HookOutcome(
                    hasOutcome = true,
                    initialResult = result,
                    initialThrowable = error
                )
                val bridge = HookParam(member, thisObject, currentArgs.copyOf(), outcome, origin)
                callback(bridge)
                outcome.throwable?.let { throw it }
                outcome.result
            }
            HookKind.REPLACE -> {
                val outcome = HookOutcome()
                val bridge = HookParam(member, thisObject, currentArgs.copyOf(), outcome, origin)
                val replaced = callback(bridge)
                outcome.throwable?.let { throw it }
                if (outcome.hasOutcome) outcome.result else replaced
            }
        }
    }

    private fun proceedApi101(chainObj: Any, args: Array<Any?>): Any? {
        return chainObj.callMethodExact(
            "proceed",
            arrayOf(Array<Any?>::class.java),
            args
        )
    }

    private fun chainArgs(chainObj: Any): Array<Any?> =
        (chainObj.callMethodAs<List<*>>("getArgs")).toTypedArray()

    private fun invokeOrigin(member: Member, thisObject: Any?, args: Array<Any?>): Any? {
        return invokeOriginApi101(member, thisObject, args)
    }

    private fun invokeOriginApi101(member: Member, thisObject: Any?, args: Array<Any?>): Any? {
        val invoker = module.callMethod("getInvoker", member) ?: return null
        invoker.callMethod("setType", XposedInterface.Invoker.Type.ORIGIN)
        return when (member) {
            is Method -> invoker.callMethodExact(
                "invoke",
                arrayOf(Any::class.java, Array<Any?>::class.java),
                thisObject,
                args
            )
            is Constructor<*> -> invoker.callMethodExact(
                "newInstance",
                arrayOf(Array<Any?>::class.java),
                args
            )
            else -> null
        }
    }
}

private fun defaultProxyValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> 0.toChar()
    else -> null
}
