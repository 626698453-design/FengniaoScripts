package h.Hchat.host

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.ui.SettingsUI
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector

/**
 * 10.11.2 确认：HBCommonMagexActivity Intent scene_name；hb-settings 为内置设置场景。
 * 在 Activity 的 android.R.id.content 中为原生入口单独预留高度，不覆盖 Mist 的触摸区域。
 */
object SettingsEntryHook {
    private const val ACTIVITY = "me.ele.lpd.dynamiclib.magex.HBCommonMagexActivity"
    private const val TAG = "fengniao.script.settings.entry"
    fun install(loader: ClassLoader) {
        runCatching {
            val clazz = KavaReflector.loadClass(ACTIVITY, loader) ?: error("HBCommonMagexActivity 不存在")
            val onCreate = KavaReflector.findDeclaredMethod(clazz, "onCreate", android.os.Bundle::class.java) ?: error("onCreate(Bundle) 不存在")
            XposedBridge.hookMethod(onCreate, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isSettings(activity)) return
                    activity.window.decorView.post { attach(activity) }
                }
            })
        }.onFailure { HLog.e("[Fengniao] 设置入口安装失败", it) }
    }
    internal fun isSettings(activity: Activity): Boolean {
        val intent = activity.intent ?: return false
        return intent.getStringExtra("scene_name") == "hb-settings"
    }
    private fun attach(a: Activity) {
        if (a.isFinishing || a.isDestroyed || !isSettings(a)) return
        runCatching {
            val content = a.findViewById<ViewGroup>(android.R.id.content) ?: error("设置页内容容器不存在")
            if (content.childCount == 0 || content.findViewWithTag<View>(TAG) != null) return
            val originals = (0 until content.childCount).map { content.getChildAt(it).let { view -> view to view.layoutParams } }
            val wrapper = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; tag = TAG }
            val body = android.widget.FrameLayout(a)
            val bar = TextView(a).apply {
                text = "脚本插件 · Agent · Codex   ›"; textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                val padding = (18 * resources.displayMetrics.density).toInt(); setPadding(padding, 0, padding, 0)
                val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                setTextColor(if (dark) Color.WHITE else Color.rgb(30, 30, 30))
                setBackgroundColor(if (dark) Color.rgb(35, 35, 35) else Color.WHITE)
                contentDescription = "打开脚本插件系统、Agent 和 Codex"
                setOnClickListener { SettingsUI.show(a) }
            }
            wrapper.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            wrapper.addView(bar, LinearLayout.LayoutParams(-1, (52 * a.resources.displayMetrics.density).toInt()))
            try {
                // Preserve original IDs, instances, order and native LayoutParams.
                originals.forEach { (view, params) -> content.removeView(view); body.addView(view, params) }
                content.addView(wrapper, ViewGroup.LayoutParams(-1, -1))
            } catch (error: Throwable) {
                content.removeView(wrapper)
                originals.forEach { (view, _) -> (view.parent as? ViewGroup)?.removeView(view) }
                originals.forEachIndexed { index, (view, params) -> content.addView(view, index, params) }
                throw error
            }
        }.onFailure { HLog.e("[Fengniao] 设置入口挂载失败", it) }
    }
}
