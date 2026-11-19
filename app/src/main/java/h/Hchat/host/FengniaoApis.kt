package h.Hchat.host

import android.content.Context
import android.os.Looper
import h.Hchat.BuildConfig
import h.Hchat.utils.KavaReflector
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.GZIPInputStream

/** 10.11.2 的精确描述符桥。静态目录不是服务端覆盖率声明。 */
class FengniaoApis private constructor(private val context: Context, val hostClassLoader: ClassLoader) {
    companion object {
        const val PACKAGE = "me.ele.crowdsource"
        const val VERSION_NAME = "10.11.2"
        const val VERSION_CODE = 1247L
        const val APK_SHA256 = "1c3540bd1446133d4161a66750b8475c41633da3e4f4c4037aaf3b05e48d18a5"
        @Volatile private var verifiedApkPath: String? = null
        @Volatile private var instance: FengniaoApis? = null
        @JvmStatic fun get(context: Context, loader: ClassLoader): FengniaoApis = synchronized(this) {
            instance ?: FengniaoApis(context.applicationContext ?: context, loader).also { instance = it }
        }
        /** 每个宿主进程启动时在后台核对原始 APK，不复用其他构建的静态证据。 */
        @JvmStatic fun verifyApk(context: Context) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "APK 校验必须在后台执行" }
            val path = context.applicationInfo.sourceDir
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.io.File(path).inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == APK_SHA256) { "宿主 APK 指纹不匹配；当前 $actual，已停止应用本次逆向证据" }
            verifiedApkPath = path
        }
    }
    private val methods = ConcurrentHashMap<String, Method>()
    private val moduleContext by lazy { context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY) }
    private val index by lazy { JSONObject(openAsset("fengniao/host-api-catalog.json").bufferedReader().use { it.readText() }) }
    private fun openAsset(name: String): java.io.InputStream {
        val path = h.Hchat.hooks.items.script.agent.ProotEnvironment.moduleApkPath()
        if (path != null) {
            val zip = java.util.zip.ZipFile(path)
            try {
                val entry = zip.getEntry("assets/$name") ?: throw java.io.FileNotFoundException(name)
                return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
                    override fun close() { try { super.close() } finally { zip.close() } }
                }
            } catch (error: Throwable) { zip.close(); throw error }
        }
        return moduleContext.assets.open(name)
    }
    private val nativeFactoryLock = Any()
    private val callbackTimeouts = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Fengniao-Native-CallbackTimeout").apply { isDaemon = true } }
    /** 仅取消回调交付；底层 sendPizza 未暴露可核实的取消句柄。 */
    class NativeRequest internal constructor(success: Consumer<Any?>?, failure: Consumer<Any?>?) : AutoCloseable {
        private val callbacks = AtomicReference(success to failure)
        @Volatile internal var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        internal fun finish(ok: Boolean, value: Any?) {
            val pair = callbacks.getAndSet(null) ?: return
            timeout?.cancel(false); timeout = null
            runCatching { (if (ok) pair.first else pair.second)?.accept(value) }
                .onFailure { h.Hchat.utils.HLog.e("[Fengniao] 网络回调失败", it) }
        }
        override fun close() { callbacks.set(null); timeout?.cancel(false); timeout = null }
    }
    /** 经 q0.d 静态确认的宿主原生 sendPizza；不伪造 token/签名，不自动重试。 */
    fun sendPizza(json: String, success: Consumer<Any?>?, failure: Consumer<Any?>?): NativeRequest {
        requireHostVersion(); checkBackground()
        val envelope = JSONObject(json); val data = envelope.getJSONObject("data")
        require(data.getString("method") in setOf("GET", "POST", "PUT")) { "仅支持大写 GET/POST/PUT" }
        require(data.optInt("streamMode", 0) == 0) { "当前桥只支持单次响应，不支持 streamMode" }
        require(data.getString("urlPath").isNotBlank()) { "urlPath 不能为空" }
        val timeoutMs = (data.optLong("connectTimeout", 20_000L) + data.optLong("readTimeout", 30_000L) + 10_000L).coerceIn(5_000L, 300_000L)
        val handle = NativeRequest(success, failure)
        handle.timeout = callbackTimeouts.schedule({ handle.finish(false, java.util.concurrent.TimeoutException("原生网络响应等待超时；底层调用可能仍在运行")) }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val callbackClass = type("Lsa0/c${'$'}a;")
            require(callbackClass.isInterface)
            val callback = Proxy.newProxyInstance(hostClassLoader, arrayOf(callbackClass)) { proxy, method, args ->
                when (method.name) {
                    "a" -> { handle.finish(true, args?.getOrNull(1)); null }
                    "b" -> { handle.finish(false, args?.getOrNull(1)); null }
                    "toString" -> "FengniaoNativeCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }
            val adapterClass = type("Lme/ele/lpd/dynamiclib/magex/adapter/q0;")
            val constructor = KavaReflector.findConstructor(adapterClass) ?: throw NoSuchMethodException("q0.<init>()")
            val adapter = KavaReflector.newInstance(constructor) ?: error("无法创建宿主 sendPizza 适配器")
            call("Lme/ele/lpd/dynamiclib/magex/adapter/q0;->d(Lme/ele/android/lmagex/h;Ljava/lang/Object;Ljava/util/Map;Lsa0/c${'$'}a;)Ljava/lang/Object;",
                adapter, arrayOf(null, hostJson(json), java.util.HashMap<String, Any?>(), callback))
        } catch (error: Throwable) { handle.close(); throw error }
        return handle
    }
    fun metadata(): JSONObject = JSONObject(index.getJSONObject("metadata").toString())
    /** Mist 资源中的路径/操作名引用，仅作为定位证据。 */
    fun mistCatalog(filter: String, offset: Int, limit: Int): JSONArray {
        checkBackground(); val query = filter.lowercase(Locale.ROOT); var skip = offset.coerceAtLeast(0); val out = JSONArray()
        val refs = JSONObject(openAsset("fengniao/mist-endpoint-references.json").bufferedReader().use { it.readText() }).getJSONArray("references")
        for (i in 0 until refs.length()) {
            val entry = refs.getJSONObject(i)
            if (query.isNotBlank() && !entry.toString().lowercase(Locale.ROOT).contains(query)) continue
            if (skip-- > 0) continue
            out.put(entry)
            if (out.length() >= limit.coerceIn(1, 500)) break
        }
        return out
    }
    @JvmOverloads fun catalog(filter: String = ""): JSONArray = catalog(filter, 0, 100)
    fun catalog(filter: String, offset: Int, limit: Int): JSONArray {
        val source = index.getJSONArray("methods"); val out = JSONArray(); var skip = offset.coerceAtLeast(0)
        val query = filter.lowercase(Locale.ROOT)
        for (i in 0 until source.length()) {
            val row = source.getJSONObject(i)
            if (query.isNotBlank() && !row.toString().lowercase(Locale.ROOT).contains(query)) continue
            if (skip-- > 0) continue
            out.put(JSONObject(row.toString()))
            if (out.length() >= limit.coerceIn(1, 1000)) break
        }
        return out
    }
    /** 全部 DEX 已声明方法（含第三方库），逐行解压，只返回一页，避免一次常驻巨型 JSON。 */
    fun searchMethods(filter: String, offset: Int, limit: Int): JSONArray {
        checkBackground(); val out = JSONArray(); var skip = offset.coerceAtLeast(0)
        val query = filter.lowercase(Locale.ROOT)
        GZIPInputStream(openAsset("fengniao/host-methods.jsonl.gz")).bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                if (query.isNotBlank() && !line.lowercase(Locale.ROOT).contains(query)) continue
                if (skip-- > 0) continue
                out.put(JSONObject(line))
                if (out.length() >= limit.coerceIn(1, 500)) break
            }
        }
        return out
    }
    /** 调用任何已确认描述符；实例成员必须显式提供真实 receiver。 */
    fun call(descriptor: String, receiver: Any?, args: Array<Any?>): Any? {
        requireHostVersion()
        val method = resolve(descriptor)
        require(args.size == method.parameterTypes.size) { "参数数量与描述符不符" }
        require(Modifier.isStatic(method.modifiers) || (receiver != null && method.declaringClass.isInstance(receiver))) { "必须提供该成员的真实实例" }
        return KavaReflector.invokeOrThrow(method, receiver, *args)
    }
    fun resolve(descriptor: String): Method = methods.getOrPut(descriptor) {
        val match = Regex("""^(L[^;]+;)->([^()]+)\((.*)\)(.+)$""").matchEntire(descriptor)
            ?: throw IllegalArgumentException("需要完整 DEX 方法描述符")
        val owner = type(match.groupValues[1]); val name = match.groupValues[2]
        val params = splitTypes(match.groupValues[3]).map(::type).toTypedArray()
        val method = KavaReflector.findDeclaredMethod(owner, name, *params)
            ?: throw NoSuchMethodException(descriptor)
        require(typeDescriptor(method.returnType) == match.groupValues[4]) { "返回类型与描述符不符" }
        method
    }
    /** 已逆向确认 f.d().l(urlKey, interfaceClass, scheduler)；只创建代理，不发送请求。 */
    fun service(urlKey: String, interfaceName: String, scheduler: Any?): Any {
        requireHostVersion(); checkBackground()
        val serviceType = type(if (interfaceName.startsWith("L") && interfaceName.endsWith(";")) interfaceName else "L" + interfaceName.replace('.', '/') + ";")
        require(serviceType.isInterface) { "service 需要接口类型" }
        return synchronized(nativeFactoryLock) {
            val factory = call("Lme/ele/lpdfoundation/network/f;->d()Lme/ele/lpdfoundation/network/f;", null, emptyArray())
                ?: error("宿主网络工厂未就绪")
            call("Lme/ele/lpdfoundation/network/f;->l(Ljava/lang/String;Ljava/lang/Class;Lrx/f;)Ljava/lang/Object;", factory, arrayOf(urlKey, serviceType, scheduler))
                ?: error("宿主没有返回 Service 代理")
        }
    }
    /** 按目录 descriptor 创建原生代理并调用，返回宿主原生 Call/Observable，不自动执行或重试。 */
    fun request(urlKey: String, descriptor: String, args: Array<Any?>): Any? {
        checkBackground()
        val method = resolve(descriptor)
        val receiver = service(urlKey, method.declaringClass.name, null)
        return call(descriptor, receiver, args)
    }
    /** 已确认 xc0.b.execute() -> android.util.Pair；调用方检查 Pair 中的业务结果。 */
    fun execute(nativeCall: Any): Any? {
        checkBackground()
        return call("Lxc0/b;->execute()Landroid/util/Pair;", nativeCall, emptyArray())
    }
    fun rpc(operationType: String, payload: Any?): Nothing = throw UnsupportedOperationException(
        "没有验证通用 OperationType 发包约定；请用 catalog 查询并通过 request(urlKey, descriptor, args) 调用具体原生 Service。")
    fun hostJson(text: String): Any? {
        requireHostVersion()
        return call("Lcom/alibaba/fastjson/JSON;->parseObject(Ljava/lang/String;)Lcom/alibaba/fastjson/JSONObject;", null, arrayOf(text))
    }
    private fun requireHostVersion() {
        val info = context.packageManager.getPackageInfo(PACKAGE, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        check(info.versionName == VERSION_NAME && code == VERSION_CODE) { "只验证蜂鸟众包 10.11.2 (1247)，当前 ${info.versionName} ($code)" }
        check(verifiedApkPath == context.applicationInfo.sourceDir) { "宿主 APK 指纹尚未验证" }
    }
    private fun checkBackground() { check(Looper.myLooper() != Looper.getMainLooper()) { "请在 async 任务中调用此接口" } }
    private fun type(descriptor: String): Class<*> = when (descriptor) {
        "Z" -> Boolean::class.javaPrimitiveType!!; "B" -> Byte::class.javaPrimitiveType!!
        "C" -> Char::class.javaPrimitiveType!!; "S" -> Short::class.javaPrimitiveType!!
        "I" -> Int::class.javaPrimitiveType!!; "J" -> Long::class.javaPrimitiveType!!
        "F" -> Float::class.javaPrimitiveType!!; "D" -> Double::class.javaPrimitiveType!!
        "V" -> Void.TYPE
        else -> KavaReflector.loadClass(if (descriptor.startsWith("L")) descriptor.substring(1, descriptor.length-1).replace('/', '.') else descriptor.replace('/', '.'), hostClassLoader)
            ?: throw ClassNotFoundException(descriptor)
    }
    private fun splitTypes(text: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < text.length) {
            val start = i
            while (i < text.length && text[i] == '[') i++
            require(i < text.length) { "无效参数描述符" }
            if (text[i] == 'L') { val end = text.indexOf(';', i); require(end >= i); i = end + 1 }
            else { require(text[i] in "ZBCSIJFD"); i++ }
            out.add(text.substring(start, i))
        }
        return out
    }
    private fun typeDescriptor(c: Class<*>): String = when {
        c.isArray -> c.name.replace('.', '/')
        !c.isPrimitive -> "L" + c.name.replace('.', '/') + ";"
        else -> mapOf(Void.TYPE to "V", Boolean::class.javaPrimitiveType to "Z", Byte::class.javaPrimitiveType to "B", Char::class.javaPrimitiveType to "C", Short::class.javaPrimitiveType to "S", Int::class.javaPrimitiveType to "I", Long::class.javaPrimitiveType to "J", Float::class.javaPrimitiveType to "F", Double::class.javaPrimitiveType to "D").getValue(c)
    }
}
