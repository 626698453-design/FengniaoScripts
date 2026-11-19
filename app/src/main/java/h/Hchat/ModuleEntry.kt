package h.Hchat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentLocalReverseTools
import h.Hchat.hooks.items.script.agent.ProotEnvironment
import h.Hchat.host.FengniaoApis
import h.Hchat.host.SettingsEntryHook
import h.Hchat.loader.utils.NativeLibraryLoader
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.atomic.AtomicBoolean

/** 仅主进程；独立模块不注册任何原 Hchat 业务功能。 */
class ModuleEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object { private val installed = AtomicBoolean() }
    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) {
        ProotEnvironment.registerModuleApkPath(param.modulePath)
        NativeLibraryLoader.registerModuleApkPath(param.modulePath)
    }
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != FengniaoApis.PACKAGE || param.processName != FengniaoApis.PACKAGE) return
        if (!Process.is64Bit() || !installed.compareAndSet(false, true)) return
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(hook: MethodHookParam) {
                val app = hook.thisObject as? Application ?: return
                val host = hook.args[0] as? Context ?: return
                val version = runCatching { host.packageManager.getPackageInfo(FengniaoApis.PACKAGE, 0) }.getOrNull() ?: return
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) version.longVersionCode else @Suppress("DEPRECATION") version.versionCode.toLong()
                if (version.versionName != FengniaoApis.VERSION_NAME || code != FengniaoApis.VERSION_CODE) {
                    HLog.e("[Fengniao] 当前宿主版本不受支持: ${version.versionName} ($code)"); return
                }
                val loader = host.classLoader ?: param.classLoader
                Thread({
                    try {
                        FengniaoApis.verifyApk(host)
                        SettingsEntryHook.install(loader)
                        var holder: DexBridgeHolder? = null
                        runCatching {
                            NativeLibraryLoader().loadDexKit(host, javaClass.classLoader)
                            val dex = DexKitBridge.createManaged(param.appInfo.sourceDir, 30_000L)
                            holder = DexBridgeHolder(dex, loader, param.appInfo.sourceDir)
                            ScriptPluginAgentLocalReverseTools.install(holder!!, host)
                        }.onFailure { HLog.e("[Fengniao] 逆向服务初始化失败，脚本基本能力仍可使用", it) }
                        ScriptPluginRuntime.install(app, loader, holder?.dexKitBridge, holder)
                    } catch (error: Throwable) { HLog.e("[Fengniao] 脚本系统初始化失败", error) }
                }, "Fengniao-Init").apply { isDaemon = true; start() }
            }
        })
    }
}
