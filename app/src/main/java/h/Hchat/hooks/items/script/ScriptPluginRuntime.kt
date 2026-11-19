package h.Hchat.hooks.items.script

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import bsh.Interpreter
import dalvik.system.DexClassLoader
import h.Hchat.BuildConfig
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/** Host-independent BeanShell lifecycle extracted from Hchat; runs only in the host main process. */
object ScriptPluginRuntime {
    data class ScriptPlugin(
        val id: String, val name: String, val dir: File, val mainFile: File,
        val author: String, val version: String, val updateTime: String,
        val displayName: String? = null, val processScope: Set<String> = setOf("main")
    )
    data class PluginRuntimeSnapshot(val initialized: Boolean, val loaded: Boolean, val processKind: String, val processName: String)
    private class Loaded(val plugin: ScriptPlugin, val interpreter: Interpreter, val utilities: ScriptWaBridge) {
        val lock = ReentrantLock(true)
        @Volatile var active = true
    }
    class PluginCatalogSubscription internal constructor(private val listener: () -> Unit) {
        fun unsubscribe() { listeners.remove(listener) }
    }
    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lifecycle = ReentrantLock(true)
    private val worker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(128), { runnable -> Thread(runnable, "Fengniao-Script-Lifecycle").apply { isDaemon = true } }, java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    private val main = Handler(Looper.getMainLooper())
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val reloads = ConcurrentHashMap<String, Runnable>()
    private val started = AtomicBoolean()
    @Volatile private var bridge: ScriptPluginBridge? = null
    @Volatile private var app: Context? = null
    private var rootObserver: FileObserver? = null
    private val refreshQueued = AtomicBoolean()
    private val callbackNames = setOf("onLoad", "onUnload", "openSettings")

    @JvmStatic @JvmOverloads
    fun install(context: Context, classLoader: ClassLoader, dexBridge: DexKitBridge? = null, holder: DexBridgeHolder? = null) {
        val host = context.applicationContext ?: context
        app = host
        bridge = ScriptPluginBridge(host, classLoader, ensureDirs(host), ScriptDexKitBridge(dexBridge, holder, classLoader))
        observe(host)
        if (started.compareAndSet(false, true)) worker.execute {
            ScriptPluginManager.recoverInterruptedOperations(host).onFailure { HLog.e("[Fengniao:Script] 恢复插件事务失败", it) }
            val prefs = HchatStorage.preferences(host, ScriptPluginSettings.PREFS_NAME)
            if (globalEnabled(host) && prefs.getBoolean(ScriptPluginSettings.KEY_RUN_ON_START, false)) {
                lifecycle.withLock { listPlugins(host).filter { isPluginEnabled(host, it.id) }.forEach { load(it).onFailure { e -> HLog.e("[Fengniao:Script] 启动加载失败: " + it.id, e) } } }
            }
        }
    }

    fun pluginRuntimeSnapshot(pluginId: String) = PluginRuntimeSnapshot(bridge != null, loaded.containsKey(pluginId), "main", app?.packageName.orEmpty())
    fun scriptDir(context: Context): File {
        val host = context.applicationContext ?: context
        val media = runCatching { host.externalMediaDirs.firstOrNull { it != null } }.getOrNull()
        val base = media ?: host.getExternalFilesDir(null) ?: host.filesDir
        return File(base, "FengniaoScript/脚本插件")
    }
    fun ensureDirs(context: Context): File = scriptDir(context).apply { check(isDirectory || mkdirs()) { "无法创建脚本插件目录" } }
    fun listPlugins(context: Context): List<ScriptPlugin> {
        val root = scriptDir(context).canonicalFile
        return root.listFiles().orEmpty().asSequence().filter { it.isDirectory && !it.name.startsWith(".") && it.canonicalFile.parentFile == root }
            .mapNotNull { dir ->
                val source = File(dir, "main.java")
                if (!source.isFile || source.canonicalFile.parentFile != dir.canonicalFile) return@mapNotNull null
                val prop = Properties()
                runCatching { File(dir, "info.prop").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { prop.load(it) } }
                val name = prop.getProperty("name")?.takeIf { it.isNotBlank() }
                val scope = if (prop.getProperty("process", "main").trim() == "main") setOf("main") else emptySet()
                ScriptPlugin(dir.name, name ?: dir.name, dir, source, prop.getProperty("author", ""), prop.getProperty("version", ""), prop.getProperty("updateTime", ""), name, scope)
            }.sortedBy { it.id.lowercase(Locale.US) }.toList()
    }
    fun isPluginEnabled(context: Context, pluginId: String): Boolean = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME).getBoolean(ScriptPluginSettings.pluginEnableKey(pluginId), false)
    private fun globalEnabled(context: Context) = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME).getBoolean(ScriptPluginSettings.KEY_ENABLE, false)
    fun setGlobalEnabled(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        lifecycle.withLock {
            check(bridge != null) { "请从蜂鸟众包设置入口使用脚本系统" }
            val prefs = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            if (enabled) {
                try {
                    listPlugins(context).filter { isPluginEnabled(context, it.id) }.forEach { load(it).getOrThrow() }
                    prefs.edit().putBoolean(ScriptPluginSettings.KEY_ENABLE, true).apply()
                } catch (error: Throwable) {
                    loaded.keys.toList().forEach { unload(it) }
                    prefs.edit().putBoolean(ScriptPluginSettings.KEY_ENABLE, false).apply()
                    throw error
                }
            } else {
                prefs.edit().putBoolean(ScriptPluginSettings.KEY_ENABLE, false).apply()
                loaded.keys.toList().forEach { unload(it) }
            }
        }
        changed()
    }
    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean): Result<Unit> = runCatching {
        ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(pluginId)) {
            lifecycle.withLock {
                if (enabled) {
                    val plugin = listPlugins(context).firstOrNull { it.id == pluginId } ?: error("插件不存在: $pluginId")
                    check(plugin.processScope.isNotEmpty()) { "蜂鸟众包模块只支持 process=main" }
                    if (globalEnabled(context)) load(plugin).getOrThrow()
                } else unload(pluginId)
                HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME).edit().putBoolean(ScriptPluginSettings.pluginEnableKey(pluginId), enabled).apply()
            }
        }
        changed()
    }
    fun reloadPlugin(context: Context, pluginId: String): Result<Unit> = runCatching {
        lifecycle.withLock {
            unload(pluginId)
            if (globalEnabled(context) && isPluginEnabled(context, pluginId)) {
                val plugin = listPlugins(context).firstOrNull { it.id == pluginId } ?: error("插件不存在: $pluginId")
                load(plugin).getOrThrow()
            }
        }
        changed()
    }
    fun reloadPluginAsync(context: Context, pluginId: String) { worker.execute { reloadPlugin(context, pluginId).onFailure { HLog.e("[Fengniao:Script] 重载失败: $pluginId", it) } } }
    fun refreshPluginObserver(context: Context, pluginId: String) { observers.remove(pluginId)?.stopWatching(); observe(context); changed() }
    fun subscribePluginCatalog(context: Context, listener: () -> Unit): PluginCatalogSubscription { observe(context); listeners.add(listener); return PluginCatalogSubscription(listener) }
    private fun changed() { main.post { listeners.forEach { runCatching(it).onFailure { e -> HLog.e("[Fengniao:Script] 插件列表监听失败", e) } } } }
    fun hasOpenSettings(pluginId: String): Boolean = loaded[pluginId]?.let { hasMethod(it.interpreter, "openSettings") } ?: false
    fun canOpenSettings(plugin: ScriptPlugin): Boolean = hasOpenSettings(plugin.id)
    fun callOpenSettings(pluginId: String): Result<Unit> = callPluginFunction(pluginId, "openSettings").map { Unit }
    fun callPluginFunction(pluginId: String, functionName: String, vararg args: Any?): Result<Any?> = runCatching {
        require(functionName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "方法名无效" }
        val state = loaded[pluginId] ?: error("插件未加载")
        state.lock.withLock {
            check(state.active) { "插件已卸载" }
            val names = args.indices.map { "__fn_argument_$it" }
            try {
                names.forEachIndexed { index, name -> state.interpreter.set(name, args[index]) }
                state.interpreter.eval(functionName + "(" + names.joinToString(",") + ");")
            } finally { names.forEach { state.interpreter.unset(it) } }
        }
    }
    internal fun withPluginLock(pluginId: String, action: () -> Unit) {
        val state = loaded[pluginId] ?: return
        state.lock.withLock { if (state.active) action() }
    }
    private fun hasMethod(interpreter: Interpreter, name: String) = interpreter.nameSpace.methods.any { it.name == name }
    private fun load(plugin: ScriptPlugin): Result<Unit> {
        if (loaded.containsKey(plugin.id)) return Result.success(Unit)
        val host = bridge ?: return Result.failure(IllegalStateException("脚本运行时未初始化"))
        if (plugin.processScope.isEmpty()) return Result.failure(IllegalStateException("只支持 process=main"))
        val utility = ScriptWaBridge(host, plugin.id).apply { bindPluginLog(plugin.name, plugin.dir) }
        var state: Loaded? = null
        return runCatching {
            val interpreter = interpreter(host, plugin, utility)
            state = Loaded(plugin, interpreter, utility)
            loaded[plugin.id] = state!!
            state!!.lock.withLock {
                interpreter.source(plugin.mainFile.absolutePath)
                if (hasMethod(interpreter, "onLoad")) interpreter.eval("onLoad();")
            }
        }.onFailure { error ->
            state?.active = false
            loaded.remove(plugin.id)
            utility.dispose()
            host.unhookPlugin(plugin.id)
            host.log(plugin.name, plugin.dir, "加载失败: " + error.toString())
            HLog.e("[Fengniao:Script] 加载失败: " + plugin.id, error)
        }.also { changed() }
    }
    private fun unload(pluginId: String) {
        val state = loaded.remove(pluginId) ?: return
        state.active = false
        state.utilities.dispose()
        state.lock.withLock {
            try {
                runCatching { if (hasMethod(state.interpreter, "onUnload")) state.interpreter.eval("onUnload();") }
                    .onFailure { HLog.e("[Fengniao:Script] onUnload 失败: $pluginId", it) }
            } finally {
                // A callback already running at cancellation may register a final Hook.
                // Clean up after its interpreter lock has been released.
                bridge?.unhookPlugin(pluginId)
            }
        }
    }
    fun useCallback(pluginId: String, interpreter: Interpreter, callbackName: String?, methodName: String?) {
        require(callbackName in callbackNames && methodName?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) == true) { "不支持的回调名称" }
        interpreter.eval("void $callbackName() { $methodName(); }")
    }
    fun evalCode(pluginId: String, interpreter: Interpreter, code: String): Any? = interpreter.eval(code)
    fun loadJava(pluginId: String, pluginDir: File, interpreter: Interpreter, path: String) { interpreter.source(resolve(pluginDir, path).absolutePath) }
    fun loadDex(pluginId: String, pluginDir: File, interpreter: Interpreter, parentClassLoader: ClassLoader, path: String): ClassLoader {
        val source = resolve(pluginDir, path)
        val context = app ?: error("运行时未初始化")
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        val root = File(context.codeCacheDir, "fengniao-script-dex").apply { mkdirs() }
        val target = File(root, digest + "." + source.extension.ifBlank { "dex" })
        if (!target.isFile) {
            java.io.FileOutputStream(target).use { output ->
                check(target.setReadOnly()) { "DEX 无法设为只读" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
        }
        return DexClassLoader(target.absolutePath, root.absolutePath, null, parentClassLoader).also { interpreter.addClassLoader(it) }
    }
    private fun resolve(pluginDir: File, path: String): File = (if (File(path).isAbsolute) File(path) else File(pluginDir, path)).canonicalFile.also { require(it.isFile) { "文件不存在: $path" } }
    private fun snapshotKey() = SecretKeySpec(byteArrayOf(48,49,50,51,52,53,54,55,56,57,97,98,99,100,101,102), "AES")
    fun compileSnapshot(pluginId: String, pluginDir: File, interpreter: Interpreter, path: String): String {
        val source = resolve(pluginDir, path); val output = File(source.path + ".bshs")
        interpreter.compileSnapshot(source.path, output.path, snapshotKey()); return output.path
    }
    fun evalSnapshot(pluginId: String, pluginDir: File, interpreter: Interpreter, path: String): Any? = interpreter.evalSnapshot(resolve(pluginDir, path).path, snapshotKey())
    fun evalSnapshot(pluginId: String, interpreter: Interpreter, input: InputStream): Any? = interpreter.evalSnapshot(input, snapshotKey())
    fun evalSnapshot(pluginId: String, interpreter: Interpreter, data: ByteArray): Any? = evalSnapshot(pluginId, interpreter, data.inputStream())

    @Synchronized private fun observe(context: Context) {
        val root = ensureDirs(context)
        if (rootObserver == null) rootObserver = object : FileObserver(root.path, CREATE or DELETE or MOVED_TO or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (refreshQueued.compareAndSet(false, true)) worker.execute {
                    try { observe(context); changed() } finally { refreshQueued.set(false) }
                }
            }
        }.also { it.startWatching() }
        val dirs = listPlugins(context).associateBy { it.id }
        observers.keys.filter { it !in dirs }.forEach { id -> observers.remove(id)?.stopWatching(); worker.execute { lifecycle.withLock { unload(id) } } }
        dirs.forEach { (id, plugin) -> if (!observers.containsKey(id)) {
            observers[id] = object : FileObserver(plugin.dir.path, CLOSE_WRITE or CREATE or DELETE or MOVED_TO or MOVED_FROM) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "main.java" || path == "info.prop" || path?.endsWith(".bsh") == true || path?.endsWith(".java") == true) {
                        val task = Runnable { reloads.remove(id); reloadPluginAsync(context, id); changed() }
                        reloads.put(id, task)?.let { main.removeCallbacks(it) }
                        main.postDelayed(task, 500)
                    }
                }
            }.also { it.startWatching() }
        } }
    }

    private fun interpreter(host: ScriptPluginBridge, plugin: ScriptPlugin, utilities: ScriptWaBridge): Interpreter = Interpreter().apply {
        addClassLoader(host.classLoader)
        set("context", host.hostContext); set("hostContext", host.hostContext); set("classLoader", host.classLoader)
        set("pluginId", plugin.id); set("pluginName", plugin.name); set("pluginAuthor", plugin.author); set("pluginVersion", plugin.version); set("pluginUpdateTime", plugin.updateTime)
        set("pluginDir", plugin.dir.path); set("pluginDirFile", plugin.dir); set("scriptDir", host.scriptDir.path); set("scriptDirFile", host.scriptDir)
        val cache = File(host.scriptDir.parentFile, "Cache").apply { mkdirs() }
        set("cacheDir", cache.path); set("cacheDirFile", cache)
        set("bridge", host); set("wa", utilities); set("http", utilities); set("httpClient", utilities)
        set("fn", host.apis); set("apis", host.apis)
        set("dexKit", host.dexKit); set("dexKitBridge", host.dexKit?.bridge()); set("dexBridgeHolder", host.dexKit?.holder())
        set("dexFinder", host.dexKit)
        set("__hchat_runtime", ScriptPluginRuntime); set("__hchat_interpreter", this)
        set("moduleVer", BuildConfig.VERSION_NAME); set("processName", host.hostContext.packageName); set("pluginProcess", "main"); set("isMainProcess", true)
        val version = host.hostContext.packageManager.getPackageInfo(host.hostContext.packageName, 0)
        set("hostVerName", version.versionName.orEmpty()); set("hostVerCode", if (android.os.Build.VERSION.SDK_INT >= 28) version.longVersionCode else version.versionCode.toLong())
        eval(PRELUDE)
    }
    private val PRELUDE = """
        import java.io.File;
        import java.io.InputStream;
        import java.util.Map;
        import java.util.List;
        import java.util.Set;
        import java.lang.reflect.Member;
        import java.lang.reflect.Method;
        import java.lang.reflect.Field;
        import java.lang.reflect.Constructor;
        import java.util.function.Consumer;
        import java.util.function.Function;
        import de.robv.android.xposed.XposedBridge;
        import de.robv.android.xposed.XposedHelpers;
        import de.robv.android.xposed.XC_MethodHook;
        import h.Hchat.utils.KavaReflector;
        import org.json.JSONObject;
        import org.json.JSONArray;
        void log(Object text) { bridge.log(pluginName, pluginDirFile, text); }
        void toast(Object text) { bridge.toast(pluginName, text); }
        void async(Runnable action) { wa.async(action); }
        void delay(long millis, Runnable action) { wa.delay(millis, action); }
        void sendPizza(String json, Consumer success, Consumer failure) { wa.sendPizza(json, success, failure); }
        Class findClass(String name) { return bridge.findClass(name); }
        Object findClassList(Object strings) { return dexKit.findClassList(strings); }
        Object findMemberList(Object strings) { return dexKit.findMemberList(strings); }
        Method firstMethod(Object target, String name) { return bridge.firstMethod(target, name); }
        Method firstMethod(Object target, String name, int count) { return bridge.firstMethod(target, name, count); }
        Constructor firstConstructor(Object target, int count) { return bridge.firstConstructor(target, count); }
        Field firstField(Object target, String name) { return bridge.firstField(target, name); }
        Object invokeMethod(Object target, String name) { return bridge.invokeMethod(target, name); }
        Object invokeMethod(Object target, String name, Object[] args) { return bridge.invokeMethod(target, name, args); }
        Object invokeMethod(Object target, String name, int count, Object[] args) { return bridge.invokeMethod(target, name, count, args); }
        Object createInstance(Object target, int count) { return bridge.createInstance(target, count); }
        Object createInstance(Object target, int count, Object[] args) { return bridge.createInstance(target, count, args); }
        Object getField(Object target, String name) { return bridge.getField(target, name); }
        void setField(Object target, String name, Object value) { bridge.setField(target, name, value); }
        Object hookBefore(Member member, Consumer callback) { return bridge.hookBefore(pluginId, member, callback); }
        Object hookAfter(Member member, Consumer callback) { return bridge.hookAfter(pluginId, member, callback); }
        Object hookReplace(Member member, Function callback) { return bridge.hookReplace(pluginId, member, callback); }
        void unhook(Object handle) { bridge.unhook(pluginId, handle); }
        String getString(String key, String fallback) { return bridge.getString(pluginDirFile, key, fallback); }
        boolean getBoolean(String key, boolean fallback) { return bridge.getBoolean(pluginDirFile, key, fallback); }
        int getInt(String key, int fallback) { return bridge.getInt(pluginDirFile, key, fallback); }
        long getLong(String key, long fallback) { return bridge.getLong(pluginDirFile, key, fallback); }
        float getFloat(String key, float fallback) { return bridge.getFloat(pluginDirFile, key, fallback); }
        Set getStringSet(String key, Set fallback) { return bridge.getStringSet(pluginDirFile, key, fallback); }
        void putString(String key, String value) { bridge.putString(pluginDirFile, key, value); }
        void putBoolean(String key, boolean value) { bridge.putBoolean(pluginDirFile, key, value); }
        void putInt(String key, int value) { bridge.putInt(pluginDirFile, key, value); }
        void putLong(String key, long value) { bridge.putLong(pluginDirFile, key, value); }
        void putFloat(String key, float value) { bridge.putFloat(pluginDirFile, key, value); }
        void putStringSet(String key, Set value) { bridge.putStringSet(pluginDirFile, key, value); }
        void reloadPlugin() { __hchat_runtime.reloadPluginAsync(hostContext, pluginId); }
        void eval(String code) { __hchat_runtime.evalCode(pluginId, __hchat_interpreter, code); }
        void loadJava(String path) { __hchat_runtime.loadJava(pluginId, pluginDirFile, __hchat_interpreter, path); }
        ClassLoader loadDex(String path) { return __hchat_runtime.loadDex(pluginId, pluginDirFile, __hchat_interpreter, classLoader, path); }
        String compileSnapshot(String path) { return __hchat_runtime.compileSnapshot(pluginId, pluginDirFile, __hchat_interpreter, path); }
        Object evalSnapshot(String path) { return __hchat_runtime.evalSnapshot(pluginId, pluginDirFile, __hchat_interpreter, path); }
        Object evalSnapshot(InputStream input) { return __hchat_runtime.evalSnapshot(pluginId, __hchat_interpreter, input); }
        Object evalSnapshot(byte[] data) { return __hchat_runtime.evalSnapshot(pluginId, __hchat_interpreter, data); }
        void useCallback(String name, String method) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, name, method); }
        void useOnLoad(String method) { useCallback("onLoad", method); }
        void useOnUnload(String method) { useCallback("onUnload", method); }
        void useOpenSettings(String method) { useCallback("openSettings", method); }
        String readFile(String path) { return new String(java.nio.file.Files.readAllBytes(new File(pluginDirFile, path).toPath()), "UTF-8"); }
        void writeFile(String path, String text) { java.nio.file.Files.write(new File(pluginDirFile, path).toPath(), text.getBytes("UTF-8")); }
    """.trimIndent()
}
