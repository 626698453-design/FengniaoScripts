package h.Hchat.hooks.items.script

import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Historical class name retained for the generic script utility binding. */
class ScriptWaBridge internal constructor(private val bridge: ScriptPluginBridge, private val pluginId: String) {
    private var pluginName = pluginId
    private var pluginDir: File? = null
    private val tasks = ScriptAsyncScope { pluginName }
    private val delays = ScriptDelayScope(false) { pluginName }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    fun bindPluginLog(name: String?, dir: File?) { pluginName = name ?: pluginId; pluginDir = dir }
    fun async(action: Runnable?) { if (action != null) tasks.submit { locked(action) } }
    fun delay(millis: Long, action: Runnable?) {
        if (action != null) delays.delay(millis, Runnable { if (tasks.isActive()) locked(action) })
    }
    private fun locked(action: Runnable) { ScriptPluginRuntime.withPluginLock(pluginId) { if (tasks.isActive()) action.run() } }
    fun get(url: String): String = request("GET", url, null, null)
    fun get(url: String, headers: Map<*, *>?): String = request("GET", url, null, headers)
    fun post(url: String, body: String): String = request("POST", url, body, null)
    fun post(url: String, body: String, headers: Map<*, *>?): String = request("POST", url, body, headers)
    fun request(method: String, url: String, body: String?, headers: Map<*, *>?): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "HTTP 必须在后台运行，请使用 async 或 requestAsync" }
        check(tasks.isActive()) { "插件已卸载" }
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> if (k != null && v != null) builder.header(k.toString(), v.toString()) }
        val bytes = if (method.equals("GET", true) || method.equals("HEAD", true)) null else (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = http.newCall(builder.method(method.uppercase(java.util.Locale.US), bytes).build())
        val cancel = { call.cancel() }
        check(tasks.registerCancellation(cancel)) { "插件已卸载" }
        return try {
            call.execute().use { response ->
                check(response.isSuccessful) { "HTTP " + response.code }
                val result = response.body?.string().orEmpty()
                check(tasks.isActive()) { "插件已卸载" }
                result
            }
        } finally { tasks.removeCancellation(cancel) }
    }
    fun requestAsync(method: String, url: String, body: String?, headers: Map<*, *>?, success: Consumer<String>?, failure: Consumer<String>?) {
        val done = tasks.callback(success)
        val failed = tasks.callback(failure)
        if (!tasks.submit {
            runCatching { request(method, url, body, headers) }.onSuccess { result ->
                failed?.cancel()
                done?.complete { callback -> ScriptPluginRuntime.withPluginLock(pluginId) { callback.accept(result) } }
            }.onFailure { error ->
                done?.cancel()
                failed?.complete { callback -> ScriptPluginRuntime.withPluginLock(pluginId) { callback.accept(error.toString()) } }
            }
        }) { done?.cancel(); failed?.cancel() }
    }
    fun dispose() { delays.dispose(); tasks.dispose(); pluginDir = null }
    /** 插件卸载时释放脚本回调；宿主原生请求可能继续至返回。 */
    fun sendPizza(json: String, success: Consumer<Any?>?, failure: Consumer<Any?>?) {
        val done = tasks.callback(success); val failed = tasks.callback(failure)
        if (done == null && failed == null) return
        val handle = java.util.concurrent.atomic.AtomicReference<h.Hchat.host.FengniaoApis.NativeRequest?>()
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        val cancel = { handle.getAndSet(null)?.close(); Unit }
        if (!tasks.registerCancellation(cancel)) { done?.cancel(); failed?.cancel(); return }
        fun finish(ok: Boolean, result: Any?) {
            if (!finished.compareAndSet(false, true)) return
            tasks.removeCancellation(cancel)
            if (!tasks.submit {
                if (ok) { failed?.cancel(); done?.complete { callback -> ScriptPluginRuntime.withPluginLock(pluginId) { callback.accept(result) } } }
                else { done?.cancel(); failed?.complete { callback -> ScriptPluginRuntime.withPluginLock(pluginId) { callback.accept(result) } } }
            }) { done?.cancel(); failed?.cancel() }
        }
        if (!tasks.submit {
            runCatching {
                val request = bridge.apis.sendPizza(json, Consumer { finish(true, it) }, Consumer { finish(false, it) })
                handle.set(request)
                if (finished.get() || !tasks.isActive()) cancel()
            }.onFailure { finish(false, it) }
        }) { tasks.removeCancellation(cancel); done?.cancel(); failed?.cancel() }
    }
}
