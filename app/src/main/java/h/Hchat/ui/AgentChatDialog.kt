package h.Hchat.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import h.Hchat.hooks.items.script.agent.*
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A small native chat frontend for the extracted, unchanged transactional Agent backend. */
internal class AgentChatDialog private constructor(activity: Activity, initial: ScriptPluginAgentSession) {
    companion object {
        private val activeSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun open(a: Activity, session: ScriptPluginAgentSession) {
            if (!activeSessions.add(session.id)) { SettingsUI.toast(a, "该会话仍在运行或停止中，请稍后打开。"); return }
            try { AgentChatDialog(a, ScriptPluginAgentSessionStore.load(a, session.id) ?: session).show() }
            catch (error: Throwable) { activeSessions.remove(session.id); HLog.e("[Fengniao:Agent] 打开会话失败", error); SettingsUI.message(a, "Agent", error.message.orEmpty()) }
        }
    }
    private val activity = WeakReference(activity)
    private val app = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var session = initial
    private var cancellation: ScriptPluginAgentCancellation? = null
    private var pending: ScriptPluginAgentWorkspaceChange? = null
    private var busy = false
    @Volatile private var closed = false
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Fengniao-AgentChat").apply { isDaemon = true } }
    private var dialog: Dialog? = null
    private var transcript: TextView? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var progress: TextView? = null
    private var send: Button? = null
    private var review: Button? = null

    private fun show() {
        val a = activity.get() ?: return
        val root = SettingsUI.column(a)
        val title = TextView(a).apply { text = "插件 Agent · ${session.title}"; textSize = 19f }; root.addView(title)
        transcript = TextView(a).apply { setTextIsSelectable(true); setPadding(12, 12, 12, 12) }
        scroll = ScrollView(a).apply { addView(transcript) }; root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = TextView(a); root.addView(progress)
        review = Button(a).apply { text = "审阅待提交修改"; visibility = android.view.View.GONE; setOnClickListener { showReview() } }; root.addView(review)
        input = EditText(a).apply { hint = "描述插件需求或继续上次任务"; minLines = 2; maxLines = 5 }; root.addView(input)
        val actions = LinearLayout(a)
        send = Button(a).apply { text = "发送"; setOnClickListener { startTurn(false) } }; actions.addView(send, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(a).apply { text = "停止"; setOnClickListener { cancellation?.cancel() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(a).apply { text = "续接"; setOnClickListener { startTurn(true) } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(a).apply { text = "关闭"; setOnClickListener { dialog?.dismiss() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        val window = Dialog(a, android.R.style.Theme_Material_Light_NoActionBar)
        dialog = window; window.setContentView(root); window.setOnDismissListener { close() }
        window.show(); window.window?.setLayout(-1, -1); window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        render()
        if (session.resumeState != null) progress?.text = "存在可续接的暂存工作区，点“续接”恢复。"
    }

    private fun render() {
        transcript?.text = session.messages.joinToString("\n\n") { message ->
            val events = message.toolEvents.joinToString("\n") { event ->
                "[${event.status}] ${event.name}\n${event.arguments}\n${event.result.take(16000)}"
            }
            "${if (message.role == "user") "我" else "Agent"}：${message.content}${if (events.isBlank()) "" else "\n$events"}"
        }
        review?.visibility = if (pending == null) android.view.View.GONE else android.view.View.VISIBLE
        send?.isEnabled = !busy && pending == null
        scroll?.post { scroll?.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun startTurn(resume: Boolean) {
        if (busy || pending != null || closed) return
        val a = activity.get() ?: return
        val text = input?.text?.toString().orEmpty().trim()
        if (!resume && text.isBlank()) return
        if (!resume && session.resumeState?.workspaceCheckpoint != null) { SettingsUI.toast(a, "请先点“续接”恢复未完成工作区，再审阅或放弃修改。"); return }
        if (resume && session.resumeState == null) { SettingsUI.toast(a, "没有未完成工作区；可发送新消息。"); return }
        val config = ScriptPluginAgentSettings.load(app)
        if (!ScriptPluginAgentSettings.isValidRequestUrl(config) || config.model.isBlank()) { SettingsUI.message(a, "Agent 配置", "请先填写有效的 API 地址和模型。"); return }
        val turnId = if (resume) session.resumeState!!.turnId else UUID.randomUUID().toString()
        if (!resume) {
            session = session.copy(messages = session.messages + ScriptPluginAgentChatMessage(role = "user", content = text, turnId = turnId), updatedAt = System.currentTimeMillis(), resumeState = null)
            input?.setText("")
        }
        ScriptPluginAgentSessionStore.saveAsync(app, session)
        busy = true; render(); progress?.text = "正在连接 Agent"
        val cancel = ScriptPluginAgentCancellation(); cancellation = cancel
        var state = session
        executor.execute {
            val request = ScriptPluginAgentRequest(
                existing = null, messages = state.messages, currentDraft = state.draft, targetPluginId = state.targetPluginId,
                conversationSummary = state.conversationSummary, compactedMessageCount = state.compactedMessageCount,
                workspaceCheckpoint = if (resume) state.resumeState?.workspaceCheckpoint else null,
                lockedTaskGoal = if (resume) state.resumeState?.taskGoal.orEmpty() else "",
                agentWorkContext = if (resume) state.resumeState?.workContext.orEmpty() else "",
                nativeToolHistory = state.nativeToolHistory, protocolTranscript = state.protocolTranscript,
                sessionId = state.id, turnId = turnId
            )
            val result = ScriptPluginAgentClient.generate(app, config, request, cancel, { confirmation -> confirmWrite(confirmation, cancel) }) { update ->
                state = state.copy(
                    resumeState = update.resumeState ?: state.resumeState,
                    nativeToolHistory = update.checkpointNativeToolHistory ?: state.nativeToolHistory,
                    protocolTranscript = update.checkpointProtocolTranscript ?: state.protocolTranscript,
                    conversationSummary = update.checkpointConversationSummary ?: state.conversationSummary,
                    compactedMessageCount = update.checkpointCompactedMessageCount ?: state.compactedMessageCount,
                    updatedAt = System.currentTimeMillis()
                )
                ScriptPluginAgentSessionStore.saveAsync(app, state)
                val snapshot = state
                main.post {
                    session = snapshot
                    if (!closed) progress?.text = update.progress.ifBlank { update.toolEvents?.lastOrNull()?.let { "${it.name}：${it.status}" }.orEmpty() }.ifBlank { update.reply.takeLast(800) }
                }
            }
            main.post {
                busy = false; cancellation = null; session = state
                result.onSuccess { turn ->
                    pending = turn.workspaceChange
                    session = session.copy(
                        messages = session.messages + ScriptPluginAgentChatMessage(role = "assistant", content = turn.reply, turnId = turnId, toolEvents = turn.toolEvents, diff = turn.diff),
                        draft = turn.draft ?: session.draft, targetPluginId = turn.targetPluginId.ifBlank { session.targetPluginId },
                        nativeToolHistory = turn.nativeToolHistory.ifBlank { session.nativeToolHistory },
                        protocolTranscript = turn.protocolTranscript.ifBlank { session.protocolTranscript },
                        title = turn.title.ifBlank { ScriptPluginAgentSessionStore.titleFrom(session.messages) }, updatedAt = System.currentTimeMillis(),
                        resumeState = if (turn.workspaceChange == null) null else session.resumeState
                    )
                    if (!closed) progress?.text = if (pending == null) "已完成本轮" else "代码已暂存，点“审阅待提交修改”查看差异。"
                }.onFailure { error ->
                    HLog.e("[Fengniao:Agent] 对话失败", error)
                    session = session.copy(messages = session.messages + ScriptPluginAgentChatMessage(role = "assistant", content = "${error.message.orEmpty()}${if (session.resumeState != null) "\n暂存恢复点已保留，可点续接。" else ""}", status = "error"), updatedAt = System.currentTimeMillis())
                    if (!closed) progress?.text = "本轮未完成"
                }
                ScriptPluginAgentSessionStore.saveAsync(app, session)
                if (!closed) render()
                else activeSessions.remove(session.id)
            }
        }
    }

    private fun confirmWrite(confirmation: ScriptPluginAgentWorkspaceToolConfirmation, cancel: ScriptPluginAgentCancellation): ScriptPluginAgentWorkspaceWriteDecision {
        val answer = AtomicReference(ScriptPluginAgentWorkspaceWriteDecision.CANCEL)
        val ready = CountDownLatch(1)
        main.post {
            val a = activity.get()
            if (closed || a == null || a.isFinishing || a.isDestroyed) { ready.countDown(); return@post }
            val body = SettingsUI.column(a).apply { SettingsUI.label(this, "${confirmation.toolName} · ${confirmation.pluginId}\n\n${confirmation.diff}") }
            AlertDialog.Builder(a).setTitle("允许本次工具操作？").setView(ScrollView(a).apply { addView(body) })
                .setPositiveButton("本次允许") { _, _ -> answer.set(ScriptPluginAgentWorkspaceWriteDecision.APPROVE_ONCE); ready.countDown() }
                .setNegativeButton("取消") { _, _ -> ready.countDown() }.setOnCancelListener { ready.countDown() }.show()
        }
        while (!ready.await(250, TimeUnit.MILLISECONDS)) if (cancel.isCancelled || closed) return ScriptPluginAgentWorkspaceWriteDecision.CANCEL
        return answer.get()
    }

    private fun showReview() {
        val change = pending ?: return
        val a = activity.get() ?: return
        val view = SettingsUI.column(a).apply { SettingsUI.label(this, "${change.pluginName}\n${if (change.deletePlugin) "将删除整个插件" else "提交后保持禁用，需要手动开启"}\n\n${change.diff}") }
        AlertDialog.Builder(a).setTitle("审阅插件修改").setView(ScrollView(a).apply { addView(view) })
            .setPositiveButton("确认提交") { _, _ ->
                busy = true; render(); progress?.text = "正在提交"
                executor.execute {
                    val result = ScriptPluginAgentWorkspaceTools.apply(app, change)
                    main.post {
                        busy = false
                        result.onSuccess { pending = null; session = session.copy(draft = null, resumeState = null, updatedAt = System.currentTimeMillis()); ScriptPluginAgentSessionStore.saveAsync(app, session); if (!closed) progress?.text = "已提交；插件保持禁用。" }
                            .onFailure { error -> if (!closed) SettingsUI.message(a, "提交失败", error.message.orEmpty()) }
                        if (!closed) render()
                        else activeSessions.remove(session.id)
                    }
                }
            }.setNeutralButton("继续保留暂存", null)
            .setNegativeButton("放弃修改") { _, _ ->
                ScriptPluginAgentWorkspaceTools.discard(app, change); pending = null; session = session.copy(draft = null, resumeState = null); ScriptPluginAgentSessionStore.saveAsync(app, session); render()
            }.show()
    }

    private fun close() {
        closed = true; cancellation?.cancel()
        // Checkpoints and staged changes are durable; only explicit discard deletes them.
        pending = null
        if (!busy) activeSessions.remove(session.id)
        executor.shutdown()
        transcript = null; input = null; scroll = null; send = null; progress = null; review = null; dialog = null
    }
}
