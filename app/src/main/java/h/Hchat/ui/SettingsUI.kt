package h.Hchat.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.hooks.items.script.agent.*
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Host-independent settings surface, limited to scripts, their Agent and Codex. */
object SettingsUI {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "Fengniao-Settings").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    @JvmStatic fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val items = arrayOf("脚本插件", "插件 Agent", "Agent 配置", "Agent Skills", "Codex 与 Ubuntu")
        AlertDialog.Builder(activity).setTitle("蜂鸟脚本").setItems(items) { _, index ->
            next(activity) { when (index) {
                0 -> scripts(activity)
                1 -> sessions(activity)
                2 -> agentConfig(activity)
                3 -> skills(activity)
                4 -> codex(activity)
            } }
        }.setNegativeButton("关闭", null).show()
    }

    private fun scripts(a: Activity) {
        val content = column(a)
        val prefs = HchatStorage.preferences(a, ScriptPluginSettings.PREFS_NAME)
        val global = Switch(a).apply {
            text = "启用脚本插件系统"
            isChecked = prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, false)
            setOnCheckedChangeListener { _, enabled ->
                background(a, "切换脚本系统", { ScriptPluginRuntime.setGlobalEnabled(it, enabled).getOrThrow() }) { scripts(a) }
            }
        }
        content.addView(global)
        val boot = Switch(a).apply {
            text = "启动时加载已启用插件"
            isChecked = prefs.getBoolean(ScriptPluginSettings.KEY_RUN_ON_START, false)
            setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean(ScriptPluginSettings.KEY_RUN_ON_START, value).apply() }
        }
        content.addView(boot)
        label(content, "目录：${ScriptPluginRuntime.scriptDir(a).absolutePath}\n新插件默认关闭，启用后才会在宿主进程执行。")
        ScriptPluginRuntime.listPlugins(a).forEach { plugin ->
            button(content, "${if (ScriptPluginRuntime.isPluginEnabled(a, plugin.id)) "●" else "○"} ${plugin.displayName ?: plugin.name}  ${plugin.version}") {
                AlertDialog.Builder(a).setTitle(plugin.name).setItems(arrayOf("启用 / 停用", "重新加载", "插件设置", "查看日志", "由 Agent 修改")) { _, action ->
                    next(a) { when (action) {
                        0 -> background(a, "切换插件", { ScriptPluginRuntime.setPluginEnabled(it, plugin.id, !ScriptPluginRuntime.isPluginEnabled(it, plugin.id)).getOrThrow() }) { scripts(a) }
                        1 -> background(a, "重新加载", { ScriptPluginRuntime.reloadPlugin(it, plugin.id).getOrThrow() }) { toast(a, "已重新加载") }
                        2 -> ScriptPluginRuntime.callOpenSettings(plugin.id).onFailure { message(a, "插件设置", it.message.orEmpty()) }
                        3 -> { val log = java.io.File(plugin.dir, "log.txt"); message(a, "${plugin.name} 日志", if (log.isFile) log.readText().takeLast(64000) else "暂无日志") }
                        4 -> AgentChatDialog.open(a, ScriptPluginAgentSessionStore.newSession().copy(targetPluginId = plugin.id))
                    } }
                }.setNegativeButton("返回", null).show()
            }
        }
        showColumn(a, "脚本插件", content)
    }

    private fun sessions(a: Activity) {
        val content = column(a)
        button(content, "新建 Agent 对话") { AgentChatDialog.open(a, ScriptPluginAgentSessionStore.newSession()) }
        ScriptPluginAgentSessionStore.list(a).forEach { session ->
            button(content, session.title) { AgentChatDialog.open(a, session) }
        }
        showColumn(a, "插件 Agent", content)
    }

    private fun agentConfig(a: Activity) {
        val current = ScriptPluginAgentSettings.load(a)
        val content = column(a)
        val url = field(content, "API 地址", current.apiBaseUrl)
        val path = field(content, "API 路径（兼容端点）", current.apiPath)
        val key = field(content, "API Key", current.apiKey, true)
        val model = field(content, "模型名称", current.model)
        val endpoint = field(content, "端点模式", current.endpointMode)
        label(content, "端点模式：openai_compatible / openai / custom_url / deepseek / openrouter / siliconflow / anthropic / gemini")
        val reasoning = field(content, "推理强度", current.reasoningEffort)
        val mcp = JSONArray().apply { current.mcpServers.forEach { server -> put(JSONObject().put("id", server.id).put("name", server.name).put("enabled", server.enabled).put("endpoint", server.endpoint).put("authorization", server.authorization)) } }
        val servers = field(content, "MCP 服务器 JSON 数组", mcp.toString(2)).apply { minLines = 3 }
        val search = CheckBox(a).apply { text = "允许公开网页搜索"; isChecked = current.webSearchEnabled }
        content.addView(search)
        val dialog = showColumn(a, "Agent 配置", content, "保存") {
            runCatching {
                val rows = JSONArray(servers.text.toString().ifBlank { "[]" })
                val parsed = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { item ->
                    ScriptPluginAgentMcpServer(item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, item.optString("name"), item.optBoolean("enabled", true), item.getString("endpoint"), item.optString("authorization"))
                } }
                ScriptPluginAgentSettings.save(a, current.copy(apiBaseUrl = url.text.toString().trim(), apiPath = path.text.toString().trim(), apiKey = key.text.toString().trim(), model = model.text.toString().trim(), endpointMode = endpoint.text.toString().trim(), reasoningEffort = reasoning.text.toString().trim(), mcpServers = parsed, webSearchEnabled = search.isChecked))
            }.onSuccess { toast(a, "配置已保存") }.onFailure { message(a, "无法保存", it.message.orEmpty()) }
        }
    }

    private fun skills(a: Activity) {
        val content = column(a)
        button(content, "新建 Skill") { skillEditor(a, null) }
        ScriptPluginAgentSkills.list(a).forEach { skill ->
            button(content, "${if (skill.enabled) "●" else "○"} ${skill.name}") {
                AlertDialog.Builder(a).setTitle(skill.name).setItems(arrayOf("编辑 SKILL.md", "启用 / 停用")) { _, action ->
                    next(a) { if (action == 0) skillEditor(a, skill.id) else {
                        ScriptPluginAgentSkills.setEnabled(a, skill.id, !skill.enabled); skills(a)
                    } }
                }.show()
            }
        }
        showColumn(a, "Agent Skills", content)
    }

    private fun skillEditor(a: Activity, id: String?) {
        val content = column(a)
        val source = id?.let { ScriptPluginAgentSkills.readDocument(a, it) } ?: "---\nname: 新技能\ndescription: 描述技能用途\n---\n\n在这里填写步骤和约束。\n"
        val text = field(content, "SKILL.md", source).apply { minLines = 12 }
        showColumn(a, "编辑 Skill", content, "保存") {
            runCatching { ScriptPluginAgentSkills.saveDocument(a, id, text.text.toString()) }
                .onSuccess { toast(a, "Skill 已保存") }.onFailure { message(a, "无法保存", it.message.orEmpty()) }
        }
    }

    private fun codex(a: Activity) {
        val content = column(a)
        label(content, "Ubuntu 状态：${ProotEnvironment.currentStatus().message}\n安装包与更新来自配置的下载服务；下载后校验 SHA-256。")
        button(content, "安装 / 补齐 Ubuntu 环境") {
            background(a, "安装 Ubuntu 环境", { ProotEnvironment.install(it).getOrThrow() }) { toast(a, "Ubuntu 环境已就绪") }
        }
        button(content, "Codex API 配置") { codexConfig(a) }
        button(content, "打开 Ubuntu / Codex 终端") { terminal(a) }
        button(content, "检查并安装 Codex CLI") {
            background(a, "检查 Codex CLI", { CodexUpdater.check(it).getOrThrow() }) { release ->
                AlertDialog.Builder(a).setTitle("Codex ${release.version}").setMessage("${release.notes}\n下载大小：${release.size / 1048576} MB").setPositiveButton("安装") { _, _ -> CodexUpdater.start(a.applicationContext, release); toast(a, "已开始后台安装，可在状态中查看") }.setNegativeButton("取消", null).show()
            }
        }
        button(content, "检查并安装 Codex 网页版") {
            background(a, "检查 Codex 网页版", { CodexWebUpdater.check(it).getOrThrow() }) { release ->
                AlertDialog.Builder(a).setTitle("Codex 网页版 ${release.version}").setMessage("${release.notes}\n下载大小：${release.size / 1048576} MB").setPositiveButton("安装") { _, _ -> CodexWebUpdater.start(a.applicationContext, release); toast(a, "已开始后台安装") }.setNegativeButton("取消", null).show()
            }
        }
        button(content, "启动并打开 Codex 网页版") { background(a, "启动 Codex 网页版", { CodexWebServer.start(it).getOrThrow() }) { CodexWebServer.openInBrowser(a) } }
        button(content, "停止 Codex 网页版") { background(a, "停止 Codex 网页版", { CodexWebServer.stop(it).getOrThrow() }) { toast(a, "已停止") } }
        button(content, "更新与服务状态") {
            message(a, "Codex 状态", "CLI：${CodexUpdater.progress()}% ${CodexUpdater.stage()}\n${CodexUpdater.lastError()}\n网页版：${CodexWebUpdater.progress()}% ${CodexWebUpdater.stage()}\n${CodexWebUpdater.lastError()}\n\n${CodexWebServer.logTail(a)}")
        }
        button(content, "下载服务地址") {
            val form = column(a); val url = field(form, "HTTPS 下载源", DistributionSettings.serviceUrl(a))
            showColumn(a, "下载服务", form, "保存") { runCatching { DistributionSettings.saveServiceUrl(a, url.text.toString()) }.onFailure { message(a, "无法保存", it.message.orEmpty()) } }
        }
        showColumn(a, "Codex 与 Ubuntu", content)
    }

    private fun codexConfig(a: Activity) {
        val profile = CodexSettings.active(a)
        val form = column(a)
        val url = field(form, "API Base URL", profile.baseUrl)
        val key = field(form, "API Key", profile.apiKey, true)
        val model = field(form, "模型", profile.model)
        val effort = field(form, "推理强度", profile.effort)
        showColumn(a, "Codex API 配置", form, "保存") {
            runCatching { CodexSettings.save(a, profile.copy(baseUrl = url.text.toString().trim(), apiKey = key.text.toString().trim(), model = model.text.toString().trim(), effort = effort.text.toString().trim())) }
                .onSuccess { toast(a, "已保存；终端启动时注入密钥") }.onFailure { message(a, "保存失败", it.message.orEmpty()) }
        }
    }

    private fun terminal(a: Activity) {
        if (!ProotEnvironment.isReady(a)) { message(a, "终端", "请先安装 Ubuntu 环境。"); return }
        val page = TermuxTerminalPage(a)
        val dialog = Dialog(a, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(page)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener { page.detach() }
        page.setOnExitRequested { dialog.dismiss() }
        dialog.show(); dialog.window?.setLayout(-1, -1)
        if (!page.start()) { dialog.dismiss(); message(a, "终端", "终端会话启动失败，请检查 LSPosed 日志。") }
    }

    internal fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
    internal fun field(content: LinearLayout, title: String, value: String, secret: Boolean = false): EditText {
        label(content, title)
        return EditText(content.context).apply {
            setText(value); setSingleLine(false); setSelectAllOnFocus(false)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    internal fun label(content: LinearLayout, text: String) = TextView(content.context).apply { this.text = text; setTextIsSelectable(true); setPadding(8, 8, 8, 8); content.addView(this) }
    internal fun button(content: LinearLayout, title: String, action: () -> Unit) = Button(content.context).apply { text = title; setOnClickListener { action() }; content.addView(this, LinearLayout.LayoutParams(-1, -2)) }
    internal fun showColumn(a: Activity, title: String, content: LinearLayout, positive: String? = null, save: (() -> Unit)? = null): AlertDialog {
        val builder = AlertDialog.Builder(a).setTitle(title).setView(ScrollView(a).apply { addView(content) }).setNegativeButton("关闭", null)
        if (positive != null) builder.setPositiveButton(positive) { _, _ -> save?.invoke() }
        return builder.show()
    }
    internal fun message(a: Activity, title: String, text: String) { if (!a.isFinishing && !a.isDestroyed) showColumn(a, title, column(a).apply { label(this, text) }) }
    internal fun toast(c: Context, text: String) = Toast.makeText(c, text, Toast.LENGTH_LONG).show()
    private fun next(a: Activity, block: () -> Unit) = a.window.decorView.postOnAnimation { if (!a.isFinishing && !a.isDestroyed) block() }
    private fun <T> background(a: Activity, title: String, operation: (Context) -> T, success: (T) -> Unit) {
        val target = WeakReference(a); val app = a.applicationContext
        val progress = AlertDialog.Builder(a).setTitle(title).setMessage("正在后台处理，可关闭此提示。").setNegativeButton("关闭提示", null).show()
        val progressRef = WeakReference(progress)
        runCatching { worker.execute {
            val result = runCatching { operation(app) }
            main.post {
                progressRef.get()?.dismiss()
                val activity = target.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess(success).onFailure { HLog.e("[Fengniao:UI] $title 失败", it); message(activity, title, it.message.orEmpty()) }
            }
        } }.onFailure { progress.dismiss(); message(a, title, "后台队列已满，请稍后重试。") }
    }
}
