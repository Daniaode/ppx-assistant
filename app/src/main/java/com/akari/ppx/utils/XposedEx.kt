@file:Suppress("unused")

package com.akari.ppx.utils

import android.content.res.XResources
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.callbacks.XC_LayoutInflated
import java.lang.IllegalArgumentException
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.Enumeration

typealias Replacer = (HookParam) -> Any?
typealias Hooker = (HookParam) -> Unit

private val WRAPPER_TO_PRIMITIVE = mapOf(
    java.lang.Boolean::class.java to java.lang.Boolean.TYPE,
    java.lang.Byte::class.java to java.lang.Byte.TYPE,
    java.lang.Short::class.java to java.lang.Short.TYPE,
    java.lang.Integer::class.java to java.lang.Integer.TYPE,
    java.lang.Long::class.java to java.lang.Long.TYPE,
    java.lang.Float::class.java to java.lang.Float.TYPE,
    java.lang.Double::class.java to java.lang.Double.TYPE,
    java.lang.Character::class.java to java.lang.Character.TYPE
)

private val PRIMITIVE_TO_WRAPPER = WRAPPER_TO_PRIMITIVE.entries.associate { (wrapper, primitive) ->
    primitive to wrapper
}

private fun Class<*>.boxed(): Class<*> = PRIMITIVE_TO_WRAPPER[this] ?: this

private fun Any?.runtimeClass(): Class<*>? = this?.javaClass

private fun resolveClassSpec(spec: Any?, classLoader: ClassLoader?): Class<*> = when (spec) {
    is Class<*> -> spec
    is String -> resolveClassName(spec, classLoader)
    else -> throw IllegalArgumentException("Unsupported parameter type spec: $spec")
}

private fun resolveClassName(name: String, classLoader: ClassLoader?): Class<*> {
    return when (name) {
        "boolean" -> java.lang.Boolean.TYPE
        "byte" -> java.lang.Byte.TYPE
        "short" -> java.lang.Short.TYPE
        "int" -> java.lang.Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        "char" -> java.lang.Character.TYPE
        "void" -> java.lang.Void.TYPE
        else -> if (name.endsWith("[]")) {
            java.lang.reflect.Array.newInstance(
                resolveClassName(name.removeSuffix("[]"), classLoader),
                0
            ).javaClass
        } else {
            Class.forName(name, false, classLoader)
        }
    }
}

private fun isAssignable(parameterType: Class<*>, argumentType: Class<*>?): Boolean {
    if (argumentType == null) {
        return !parameterType.isPrimitive
    }
    if (parameterType == argumentType) {
        return true
    }
    if (parameterType.isPrimitive) {
        return parameterType == WRAPPER_TO_PRIMITIVE[argumentType]
    }
    val boxedArg = if (argumentType.isPrimitive) argumentType.boxed() else argumentType
    return parameterType.isAssignableFrom(boxedArg)
}

private fun matchScore(parameterType: Class<*>, argumentType: Class<*>?): Int {
    if (!isAssignable(parameterType, argumentType)) {
        return Int.MIN_VALUE
    }
    if (argumentType == null) {
        return 1
    }
    if (parameterType == argumentType) {
        return 8
    }
    if (parameterType.isPrimitive && WRAPPER_TO_PRIMITIVE[argumentType] == parameterType) {
        return 7
    }
    if (!parameterType.isPrimitive && argumentType.isPrimitive && parameterType == argumentType.boxed()) {
        return 7
    }
    return when {
        parameterType.isAssignableFrom(argumentType) -> 6
        !parameterType.isPrimitive && parameterType.isAssignableFrom(argumentType.boxed()) -> 5
        else -> 4
    }
}

private fun <T : Member> findBestMember(
    members: Sequence<T>,
    parameterTypes: Array<Class<*>?>
): T {
    var best: T? = null
    var bestScore = Int.MIN_VALUE
    members.forEach memberLoop@{ member ->
        val declared = when (member) {
            is Method -> member.parameterTypes
            is Constructor<*> -> member.parameterTypes
            else -> emptyArray()
        }
        if (declared.size != parameterTypes.size) {
            return@memberLoop
        }
        var score = 0
        for (index in declared.indices) {
            val match = matchScore(declared[index], parameterTypes[index])
            if (match == Int.MIN_VALUE) {
                score = Int.MIN_VALUE
                return@memberLoop
            }
            score += match
        }
        if (score > bestScore) {
            best = member
            bestScore = score
        }
    }
    return best?.also { (it as AccessibleObject).isAccessible = true } ?: throw NoSuchMethodException()
}

private fun declaredMethodsHierarchy(clazz: Class<*>): Sequence<Method> = sequence {
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredMethods.forEach { yield(it) }
        current = current.superclass
    }
}

private fun declaredFieldsHierarchy(clazz: Class<*>): Sequence<Field> = sequence {
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredFields.forEach { yield(it) }
        current = current.superclass
    }
}

private fun findMethodInternal(
    clazz: Class<*>,
    methodName: String?,
    parameterTypes: Array<Class<*>?>
): Method = findBestMember(
    declaredMethodsHierarchy(clazz).filter { it.name == methodName },
    parameterTypes
)

private fun findConstructorInternal(
    clazz: Class<*>,
    parameterTypes: Array<Class<*>?>
): Constructor<*> = findBestMember(clazz.declaredConstructors.asSequence(), parameterTypes)

private fun explicitParameterTypes(owner: Class<*>, specs: Array<out Any?>): Array<Class<*>?> =
    specs.map { resolveClassSpec(it, owner.classLoader) }.toTypedArray()

private fun runtimeParameterTypes(args: Array<out Any?>): Array<Class<*>?> =
    args.map { it.runtimeClass() }.toTypedArray()

private fun hookMember(member: Member, kind: HookKind, callback: (HookParam) -> Any?) =
    HookRuntime.backend().hook(member, kind, callback)

fun Class<*>.hookMethod(method: String?, vararg args: Any?) = try {
    findMethodInternal(this, method, explicitParameterTypes(this, args))
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun Member.replaceMethod(replacer: Replacer) =
    hookMember(this, HookKind.REPLACE) { param ->
        try {
            replacer(param)
        } catch (e: Throwable) {
            Log.e(e)
            null
        }
    }

fun Member.hookAfterMethod(hooker: Hooker) =
    hookMember(this, HookKind.AFTER) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Member.hookBeforeMethod(hooker: Hooker) =
    hookMember(this, HookKind.BEFORE) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.hookBeforeMethod(
    method: String?,
    vararg args: Any?,
    hooker: Hooker
) = try {
    findMethodInternal(this, method, explicitParameterTypes(this, args)).hookBeforeMethod(hooker)
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun Class<*>.hookAfterMethod(
    method: String?,
    vararg args: Any?,
    hooker: Hooker
) = try {
    findMethodInternal(this, method, explicitParameterTypes(this, args)).hookAfterMethod(hooker)
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun Class<*>.replaceMethod(
    method: String?,
    vararg args: Any?,
    replacer: Replacer
) = try {
    findMethodInternal(this, method, explicitParameterTypes(this, args)).replaceMethod(replacer)
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun Class<*>.hookAllMethods(methodName: String?, hooker: Hooker): Set<Any> =
    HookRuntime.backend().hookAllMethods(this, methodName, HookKind.BEFORE) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.hookBeforeAllMethods(methodName: String?, hooker: Hooker) =
    HookRuntime.backend().hookAllMethods(this, methodName, HookKind.BEFORE) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.hookAfterAllMethods(methodName: String?, hooker: Hooker) =
    HookRuntime.backend().hookAllMethods(this, methodName, HookKind.AFTER) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.replaceAfterAllMethods(methodName: String?, replacer: Replacer) =
    HookRuntime.backend().hookAllMethods(this, methodName, HookKind.REPLACE) { param ->
        try {
            replacer(param)
        } catch (e: Throwable) {
            Log.e(e)
            null
        }
    }

fun Class<*>.hookConstructor(vararg args: Any?) = try {
    findConstructorInternal(this, explicitParameterTypes(this, args))
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun Class<*>.hookBeforeConstructor(vararg args: Any?, hooker: Hooker) =
    try {
        findConstructorInternal(this, explicitParameterTypes(this, args)).hookBeforeMethod(hooker)
    } catch (e: Throwable) {
        Log.e(e)
        null
    }

fun Class<*>.hookAfterConstructor(vararg args: Any?, hooker: Hooker) =
    try {
        findConstructorInternal(this, explicitParameterTypes(this, args)).hookAfterMethod(hooker)
    } catch (e: Throwable) {
        Log.e(e)
        null
    }

fun Class<*>.replaceConstructor(vararg args: Any?, hooker: Hooker) =
    try {
        findConstructorInternal(this, explicitParameterTypes(this, args)).replaceMethod {
            hooker(it)
            null
        }
    } catch (e: Throwable) {
        Log.e(e)
        null
    }

fun Class<*>.hookAllConstructors(hooker: Hooker): Set<Any> =
    HookRuntime.backend().hookAllConstructors(this, HookKind.BEFORE) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.hookAfterAllConstructors(hooker: Hooker) =
    HookRuntime.backend().hookAllConstructors(this, HookKind.AFTER) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.hookBeforeAllConstructors(hooker: Hooker) =
    HookRuntime.backend().hookAllConstructors(this, HookKind.BEFORE) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun Class<*>.replaceAfterAllConstructors(hooker: Hooker) =
    HookRuntime.backend().hookAllConstructors(this, HookKind.REPLACE) { param ->
        try {
            hooker(param)
        } catch (e: Throwable) {
            Log.e(e)
        }
        null
    }

fun String.hookMethod(classLoader: ClassLoader, method: String?, vararg args: Any?) = try {
    findClass(classLoader).hookMethod(method, *args)
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun String.hookBeforeMethod(
    classLoader: ClassLoader,
    method: String?,
    vararg args: Any?,
    hooker: Hooker
) = try {
    findClass(classLoader).hookBeforeMethod(method, *args, hooker = hooker)
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun String.hookAfterMethod(
    classLoader: ClassLoader,
    method: String?,
    vararg args: Any?,
    hooker: Hooker
) = try {
    findClass(classLoader).hookAfterMethod(method, *args, hooker = hooker)
} catch (e: Throwable) {
    Log.e(e)
    null
}

fun String.replaceMethod(
    classLoader: ClassLoader,
    method: String?,
    vararg args: Any?,
    replacer: Replacer
) = try {
    findClass(classLoader).replaceMethod(method, *args, replacer = replacer)
} catch (e: Throwable) {
    Log.e(e)
    null
}

inline fun <T, R> T.runCatchingOrNull(func: T.() -> R?) = try {
    func()
} catch (_: Throwable) {
    null
}

fun Any?.getObjectField(field: String?): Any? = requireNotNull(this).javaClass.findField(field)
    .apply { isAccessible = true }
    .get(this)

fun Any?.getObjectFieldOrNull(field: String?): Any? = runCatchingOrNull {
    getObjectField(field)
}

@Suppress("UNCHECKED_CAST")
fun <T> Any?.getObjectFieldAs(field: String?) = getObjectField(field) as T

@Suppress("UNCHECKED_CAST")
fun <T> Any?.getObjectFieldOrNullAs(field: String?) = runCatchingOrNull {
    getObjectField(field) as T
}

fun Any?.getIntField(field: String?) = requireNotNull(this).javaClass.findField(field)
    .apply { isAccessible = true }
    .getInt(this)

fun Any?.getIntFieldOrNull(field: String?) = runCatchingOrNull {
    getIntField(field)
}

fun Any?.getLongField(field: String?) = requireNotNull(this).javaClass.findField(field)
    .apply { isAccessible = true }
    .getLong(this)

fun Any?.getLongFieldOrNull(field: String?) = runCatchingOrNull {
    getLongField(field)
}

fun Any?.getBooleanField(field: String?) =
    requireNotNull(this).javaClass.findField(field).apply { isAccessible = true }.getBoolean(this)

fun Any?.getBooleanFieldOrNull(field: String?) = runCatchingOrNull {
    getBooleanField(field)
}

fun Any?.callMethod(methodName: String?, vararg args: Any?): Any? =
    requireNotNull(this).javaClass.findMethod(methodName, runtimeParameterTypes(args)).invoke(this, *args)

fun Any?.callMethodOrNull(methodName: String?, vararg args: Any?): Any? = runCatchingOrNull {
    callMethod(methodName, *args)
}

fun Class<*>.callStaticMethod(methodName: String?, vararg args: Any?): Any? =
    findMethod(methodName, runtimeParameterTypes(args)).invoke(null, *args)

fun Class<*>.callStaticMethodOrNull(methodName: String?, vararg args: Any?): Any? =
    runCatchingOrNull {
        callStaticMethod(methodName, *args)
    }

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.callStaticMethodAs(methodName: String?, vararg args: Any?) =
    callStaticMethod(methodName, *args) as T

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.callStaticMethodOrNullAs(methodName: String?, vararg args: Any?) =
    runCatchingOrNull {
        callStaticMethod(methodName, *args) as T
    }

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.getStaticObjectFieldAs(field: String?) =
    getStaticObjectField(field) as T

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.getStaticObjectFieldOrNullAs(field: String?) = runCatchingOrNull {
    getStaticObjectField(field) as T
}

fun Class<*>.getStaticObjectField(field: String?): Any? =
    findField(field).apply { isAccessible = true }.get(null)

fun Class<*>.getStaticObjectFieldOrNull(field: String?): Any? = runCatchingOrNull {
    getStaticObjectField(field)
}

fun Class<*>.setStaticObjectField(field: String?, obj: Any?) = apply {
    findField(field).apply { isAccessible = true }.set(null, obj)
}

fun Class<*>.setStaticObjectFieldIfExist(field: String?, obj: Any?) = apply {
    runCatchingOrNull { setStaticObjectField(field, obj) }
}

inline fun <reified T> Class<*>.findFieldByExactType(): Field? =
    runCatchingOrNull { findFirstFieldByExactType(T::class.java) }

fun Class<*>.findFieldByExactType(type: Class<*>): Field? =
    runCatchingOrNull { findFirstFieldByExactType(type) }

@Suppress("UNCHECKED_CAST")
fun <T> Any?.callMethodAs(methodName: String?, vararg args: Any?) =
    callMethod(methodName, *args) as T

@Suppress("UNCHECKED_CAST")
fun <T> Any?.callMethodOrNullAs(methodName: String?, vararg args: Any?) = runCatchingOrNull {
    callMethod(methodName, *args) as T
}

fun Any?.callMethodExact(methodName: String?, parameterTypes: kotlin.Array<Class<*>>, vararg args: Any?): Any? =
    requireNotNull(this).javaClass.findMethod(methodName, parameterTypes.map { it as Class<*>? }.toTypedArray())
        .invoke(this, *args)

fun Any?.callMethodExactOrNull(
    methodName: String?,
    parameterTypes: kotlin.Array<Class<*>>,
    vararg args: Any?
): Any? = runCatchingOrNull {
    callMethodExact(methodName, parameterTypes, *args)
}

fun Class<*>.callStaticMethodExact(
    methodName: String?,
    parameterTypes: kotlin.Array<Class<*>>,
    vararg args: Any?
): Any? = findMethod(methodName, parameterTypes.map { it as Class<*>? }.toTypedArray())
    .invoke(null, *args)

fun Class<*>.callStaticMethodExactOrNull(
    methodName: String?,
    parameterTypes: kotlin.Array<Class<*>>,
    vararg args: Any?
): Any? = runCatchingOrNull {
    callStaticMethodExact(methodName, parameterTypes, *args)
}

fun String.findClass(classLoader: ClassLoader): Class<*> = Class.forName(this, false, classLoader)

fun Class<*>.new(vararg args: Any?): Any =
    findConstructorInternal(this, runtimeParameterTypes(args)).newInstance(*args)

fun Class<*>.new(parameterTypes: Array<Class<*>>, vararg args: Any?): Any =
    findConstructorInternal(this, parameterTypes.map { it as Class<*>? }.toTypedArray()).newInstance(*args)

fun Class<*>.findField(field: String?): Field = declaredFieldsHierarchy(this)
    .firstOrNull { it.name == field }
    ?.apply { isAccessible = true }
    ?: throw NoSuchFieldException(field)

fun Class<*>.findFieldOrNull(field: String?): Field? = runCatchingOrNull {
    findField(field)
}

fun <T : Any> T?.setIntField(field: String?, value: Int) = apply {
    requireNotNull(this).javaClass.findField(field).apply { isAccessible = true }.setInt(this, value)
}

fun <T : Any> T?.setLongField(field: String?, value: Long) = apply {
    requireNotNull(this).javaClass.findField(field).apply { isAccessible = true }.setLong(this, value)
}

fun <T : Any> T?.setObjectField(field: String?, value: Any?) = apply {
    requireNotNull(this).javaClass.findField(field).apply { isAccessible = true }.set(this, value)
}

fun <T : Any> T?.setBooleanField(field: String?, value: Boolean) = apply {
    requireNotNull(this).javaClass.findField(field).apply { isAccessible = true }.setBoolean(this, value)
}

fun XResources.hookLayout(
    id: Int,
    hooker: (XC_LayoutInflated.LayoutInflatedParam) -> Unit
) {
    try {
        hookLayout(id, object : XC_LayoutInflated() {
            override fun handleLayoutInflated(liparam: LayoutInflatedParam) {
                try {
                    hooker(liparam)
                } catch (e: Throwable) {
                    Log.e(e)
                }
            }
        })
    } catch (e: Throwable) {
        Log.e(e)
    }
}

fun XResources.hookLayout(
    pkg: String,
    type: String,
    name: String,
    hooker: (XC_LayoutInflated.LayoutInflatedParam) -> Unit
) {
    try {
        val id = getIdentifier(name, type, pkg)
        hookLayout(id, hooker)
    } catch (e: Throwable) {
        Log.e(e)
    }
}

fun Class<*>.findFirstFieldByExactType(type: Class<*>): Field =
    declaredFieldsHierarchy(this)
        .firstOrNull { it.type == type }
        ?.apply { isAccessible = true }
        ?: throw NoSuchFieldException(type.name)

fun Class<*>.findFirstFieldByExactTypeOrNull(type: Class<*>?): Field? = runCatchingOrNull {
    type?.let { findFirstFieldByExactType(it) }
}

fun Any?.getFirstFieldByExactType(type: Class<*>): Any? =
    requireNotNull(this).javaClass.findFirstFieldByExactType(type).get(this)

@Suppress("UNCHECKED_CAST")
fun <T> Any?.getFirstFieldByExactTypeAs(type: Class<*>) =
    requireNotNull(this).javaClass.findFirstFieldByExactType(type).get(this) as? T

inline fun <reified T : Any> Any?.getFirstFieldByExactType() =
    requireNotNull(this).javaClass.findFirstFieldByExactType(T::class.java).get(this) as? T

fun Any?.getFirstFieldByExactTypeOrNull(type: Class<*>?): Any? = runCatchingOrNull {
    requireNotNull(this).javaClass.findFirstFieldByExactTypeOrNull(type)?.get(this)
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.getFirstFieldByExactTypeOrNullAs(type: Class<*>?) =
    getFirstFieldByExactTypeOrNull(type) as? T

inline fun <reified T> Any.getFirstFieldByExactTypeOrNull() =
    getFirstFieldByExactTypeOrNull(T::class.java) as? T

fun Class<*>.findMethod(methodName: String?, parameterTypes: Array<Class<*>?>): Method =
    findMethodInternal(this, methodName, parameterTypes)

fun ClassLoader.allClassesList(
    delegator: (BaseDexClassLoader) -> BaseDexClassLoader = { x -> x }
): List<String> {
    var classLoader = this
    while (classLoader !is BaseDexClassLoader) {
        if (classLoader.parent != null) classLoader = classLoader.parent
        else return emptyList()
    }
    return delegator(classLoader).getObjectField("pathList")
        ?.getObjectFieldAs<Array<Any>>("dexElements")
        ?.flatMap {
            it.getObjectField("dexFile")
                ?.callMethodAs<Enumeration<String>>("entries")
                ?.toList()
                .orEmpty()
        }.orEmpty()
}
