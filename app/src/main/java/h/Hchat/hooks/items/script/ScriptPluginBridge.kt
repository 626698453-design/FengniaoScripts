package h.Hchat.hooks.items.script

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import h.Hchat.host.FengniaoApis
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.function.Function

class ScriptPluginBridge internal constructor(
    val hostContext: Context,
    val classLoader: ClassLoader,
    val scriptDir: File,
    val dexKit: ScriptDexKitBridge? = null
) {
    val apis = FengniaoApis.get(hostContext, classLoader)
    private val configLock = Any()
    private val pluginHooks = ConcurrentHashMap<String, CopyOnWriteArrayList<XC_MethodHook.Unhook>>()
    private val main = Handler(Looper.getMainLooper())

    fun log(pluginName: String?, pluginDir: File?, message: Any?) {
        val text = message?.toString() ?: "null"
        XposedBridge.log("[Fengniao:Script] [" + pluginName.orEmpty() + "] " + text)
        if (pluginDir == null) return
        runCatching {
            val logFile = File(pluginDir, "log.txt")
            synchronized(configLock) {
                if (logFile.length() > 2 * 1024 * 1024) logFile.writeText("")
                logFile.appendText("[" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "] " + text + "\n")
            }
        }.onFailure { h.Hchat.utils.HLog.e("[Fengniao:Script] 写入日志失败", it) }
    }

    fun toast(pluginName: String?, message: Any?) {
        val text = "[" + pluginName.orEmpty() + "] " + (message ?: "null")
        main.post { Toast.makeText(hostContext, text, Toast.LENGTH_SHORT).show() }
    }

    fun prefs(name: String) = HchatStorage.preferences(hostContext, name)

    fun file(name: String): File = File(scriptDir, name)

    fun getString(pluginDir: File?, key: String?, defaultValue: String?): String {
        val normalizedKey = key?.takeIf { it.isNotBlank() } ?: return defaultValue.orEmpty()
        return loadPluginConfig(pluginDir).getProperty(normalizedKey) ?: defaultValue.orEmpty()
    }

    fun getStringSet(pluginDir: File?, key: String?, defaultValue: Set<*>?): Set<String> {
        val value = getString(pluginDir, key, null).takeIf { it.isNotEmpty() } ?: return normalizeStringSet(defaultValue)
        return value.split('\n')
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    fun getBoolean(pluginDir: File?, key: String?, defaultValue: Boolean): Boolean {
        val value = getString(pluginDir, key, null).lowercase(Locale.US)
        return when (value) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> defaultValue
        }
    }

    fun getInt(pluginDir: File?, key: String?, defaultValue: Int): Int {
        return getString(pluginDir, key, null).toIntOrNull() ?: defaultValue
    }

    fun getFloat(pluginDir: File?, key: String?, defaultValue: Float): Float {
        return getString(pluginDir, key, null).toFloatOrNull() ?: defaultValue
    }

    fun getLong(pluginDir: File?, key: String?, defaultValue: Long): Long {
        return getString(pluginDir, key, null).toLongOrNull() ?: defaultValue
    }

    fun putString(pluginDir: File?, key: String?, value: String?) {
        putConfigValue(pluginDir, key, value.orEmpty())
    }

    fun putStringSet(pluginDir: File?, key: String?, value: Set<*>?) {
        putConfigValue(pluginDir, key, normalizeStringSet(value).joinToString("\n"))
    }

    fun putBoolean(pluginDir: File?, key: String?, value: Boolean) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun putInt(pluginDir: File?, key: String?, value: Int) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun putFloat(pluginDir: File?, key: String?, value: Float) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun putLong(pluginDir: File?, key: String?, value: Long) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun findClass(className: String): Class<*> {
        return XposedHelpers.findClass(className, classLoader)
    }

    fun firstMethod(instance: Any?, methodName: String?): Method? = firstMethod(instance, methodName, -1)

    fun firstMethod(instance: Any?, methodName: String?, paramCount: Int): Method? {
        if (methodName.isNullOrBlank()) return null
        var current = targetClass(instance)
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull {
                it.name == methodName && (paramCount < 0 || it.parameterTypes.size == paramCount)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    fun firstConstructor(instance: Any?, paramCount: Int): Constructor<*>? {
        val clazz = targetClass(instance) ?: return null
        return KavaReflector.declaredConstructors(clazz)
            .firstOrNull { it.parameterTypes.size == paramCount }
    }

    fun firstField(instance: Any?, fieldName: String?): Field? {
        if (fieldName.isNullOrBlank()) return null
        return KavaReflector.findFieldRecursive(targetClass(instance), fieldName)
    }

    fun invokeMethod(instance: Any?, methodName: String?): Any? {
        return invokeMethod(instance, methodName, 0, emptyArray())
    }

    fun invokeMethod(instance: Any?, methodName: String?, params: Array<Any?>?): Any? {
        val safeParams = params ?: emptyArray()
        return invokeMethod(instance, methodName, safeParams.size, safeParams)
    }

    fun invokeMethod(instance: Any?, methodName: String?, paramCount: Int): Any? {
        return invokeMethod(instance, methodName, paramCount, emptyArray())
    }

    fun invokeMethod(instance: Any?, methodName: String?, paramCount: Int, params: Array<Any?>?): Any? {
        val safeParams = params ?: emptyArray()
        val method = compatibleMethod(instance, methodName, paramCount, safeParams) ?: return null
        return KavaReflector.invoke(method, receiverFor(instance, method), *safeParams)
    }

    fun createInstance(instance: Any?, paramCount: Int): Any? {
        return createInstance(instance, paramCount, emptyArray())
    }

    fun createInstance(instance: Any?, paramCount: Int, params: Array<Any?>?): Any? {
        val clazz = targetClass(instance) ?: return null
        val safeParams = params ?: emptyArray()
        val constructor = KavaReflector.declaredConstructors(clazz).firstOrNull {
            it.parameterTypes.size == paramCount && areAssignable(it.parameterTypes, safeParams)
        } ?: return null
        return KavaReflector.newInstance(constructor, *safeParams)
    }

    fun getField(instance: Any?, fieldName: String?): Any? {
        val field = firstField(instance, fieldName) ?: return null
        return KavaReflector.readField(field, receiverFor(instance, field))
    }

    fun setField(instance: Any?, fieldName: String?, value: Any?) {
        val field = firstField(instance, fieldName) ?: return
        KavaReflector.writeField(field, receiverFor(instance, field), value)
    }

    fun hookBefore(
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook = hookBefore(null, member, callback)

    fun hookBefore(
        pluginId: String?,
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook {
        val unhook = XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                ScriptPluginRuntime.withPluginLock(pluginId.orEmpty()) { callback.accept(param) }
            }
        })
        rememberHook(pluginId, unhook)
        return unhook
    }

    fun hookAfter(
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook = hookAfter(null, member, callback)

    fun hookAfter(
        pluginId: String?,
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook {
        val unhook = XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                ScriptPluginRuntime.withPluginLock(pluginId.orEmpty()) { callback.accept(param) }
            }
        })
        rememberHook(pluginId, unhook)
        return unhook
    }

    fun hookReplace(
        member: Member,
        callback: Function<XC_MethodHook.MethodHookParam, Any?>
    ): XC_MethodHook.Unhook = hookReplace(null, member, callback)

    fun hookReplace(
        pluginId: String?,
        member: Member,
        callback: Function<XC_MethodHook.MethodHookParam, Any?>
    ): XC_MethodHook.Unhook {
        val unhook = XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                ScriptPluginRuntime.withPluginLock(pluginId.orEmpty()) { param.result = callback.apply(param) }
            }
        })
        rememberHook(pluginId, unhook)
        return unhook
    }

    fun unhook(unhook: XC_MethodHook.Unhook?) {
        unhook?.unhook()
        if (unhook != null) {
            pluginHooks.values.forEach { it.remove(unhook) }
        }
    }

    fun unhook(pluginId: String?, unhook: XC_MethodHook.Unhook?) {
        if (pluginId != null && unhook != null) {
            pluginHooks[pluginId]?.remove(unhook)
        }
        unhook(unhook)
    }

    fun unhook(pluginId: String?, handle: Any?) {
        unhook(pluginId, handle as? XC_MethodHook.Unhook)
    }

    fun unhookPlugin(pluginId: String?) {
        if (pluginId.isNullOrBlank()) return
        for (hook in pluginHooks.remove(pluginId).orEmpty()) {
            runCatching { hook.unhook() }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 解除插件 Hook 失败: $pluginId", it)
            }
        }
    }

    private fun rememberHook(pluginId: String?, unhook: XC_MethodHook.Unhook?) {
        if (pluginId.isNullOrBlank() || unhook == null) return
        pluginHooks.getOrPut(pluginId) { CopyOnWriteArrayList() }.add(unhook)
    }

    private fun targetClass(instance: Any?): Class<*>? {
        return when (instance) {
            null -> null
            is Class<*> -> instance
            else -> instance.javaClass
        }
    }

    private fun receiverFor(instance: Any?, method: Method): Any? {
        if (instance is Class<*> || KavaReflector.isStatic(method)) return null
        return instance
    }

    private fun receiverFor(instance: Any?, field: Field): Any? {
        if (instance is Class<*> || KavaReflector.isStatic(field)) return null
        return instance
    }

    private fun compatibleMethod(
        instance: Any?,
        methodName: String?,
        paramCount: Int,
        params: Array<Any?>
    ): Method? {
        if (methodName.isNullOrBlank()) return null
        var current = targetClass(instance)
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull {
                it.name == methodName
                        && it.parameterTypes.size == paramCount
                        && areAssignable(it.parameterTypes, params)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun areAssignable(parameterTypes: Array<Class<*>>, params: Array<Any?>): Boolean {
        if (parameterTypes.size != params.size) return false
        return parameterTypes.indices.all { isAssignable(parameterTypes[it], params[it]) }
    }

    private fun isAssignable(parameterType: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameterType.isPrimitive
        return boxType(parameterType).isAssignableFrom(value.javaClass)
    }

    private fun boxType(clazz: Class<*>): Class<*> {
        if (!clazz.isPrimitive) return clazz
        return when (clazz) {
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> clazz
        }
    }

    private fun loadPluginConfig(pluginDir: File?): Properties {
        val properties = Properties()
        val file = configFile(pluginDir) ?: return properties
        if (!file.isFile) return properties
        synchronized(configLock) {
            runCatching {
                withConfigFileLock(file) {
                    if (file.isFile) {
                        file.reader(Charsets.UTF_8).use { properties.load(it) }
                    }
                }
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 读取插件配置失败: ${pluginDir?.name.orEmpty()} ${it.message}", it)
            }
        }
        return properties
    }

    private fun putConfigValue(pluginDir: File?, key: String?, value: String) {
        val normalizedKey = key?.takeIf { it.isNotBlank() } ?: return
        val file = configFile(pluginDir) ?: return
        synchronized(configLock) {
            runCatching {
                withConfigFileLock(file) {
                    val properties = Properties()
                    if (file.isFile) {
                        file.reader(Charsets.UTF_8).use { properties.load(it) }
                    }
                    properties.setProperty(normalizedKey, value)
                    file.writer(Charsets.UTF_8).use {
                        properties.store(it, "Hchat script plugin config")
                    }
                }
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 写入插件配置失败: ${pluginDir?.name.orEmpty()} ${it.message}", it)
            }
        }
    }

    private fun configFile(pluginDir: File?): File? {
        val dir = pluginDir ?: return null
        return File(dir, "config.prop")
    }

    private inline fun <T> withConfigFileLock(file: File, action: () -> T): T {
        val parent = file.parentFile ?: error("插件配置目录不可用")
        if (!parent.isDirectory && !parent.mkdirs()) {
            error("无法创建插件配置目录: ${parent.absolutePath}")
        }
        val lockRoot = File(HchatStorage.storageDir(hostContext), "script_plugin_locks")
        if (!lockRoot.isDirectory && !lockRoot.mkdirs()) {
            error("无法创建插件配置锁目录: ${lockRoot.absolutePath}")
        }
        val lockName = "${Integer.toHexString(parent.absolutePath.hashCode())}.lock"
        val lockFile = File(lockRoot, lockName)
        return RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
            randomAccessFile.channel.use { channel ->
                val lock = channel.lock()
                try {
                    action()
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun normalizeStringSet(value: Set<*>?): Set<String> {
        if (value.isNullOrEmpty()) return emptySet()
        return value.mapNotNull { it?.toString() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

}
