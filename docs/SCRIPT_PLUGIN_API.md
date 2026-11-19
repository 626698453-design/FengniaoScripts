# 蜂鸟众包脚本插件 API

目标：蜂鸟众包 **10.11.2 / versionCode 1247 / me.ele.crowdsource**，只在主进程运行。
此运行时由 Hchat alt-entry 的 BeanShell 引擎、插件事务管理、Agent 工具和 Codex 环境提取；微信消息、联系人、红包、朋友圈与微信菜单接口不适用于此模块。

## 安装与生命周期

插件放在宿主 externalMediaDir/FengniaoScript/脚本插件/插件ID/。实际根目录可在模块页面查看；脚本只使用 pluginDirFile 等环境变量，不硬编码 Android 用户号或存储路径。媒体目录不可用时使用宿主外部文件目录，最后回退宿主私有文件目录。

每个插件至少含 main.java 与 info.prop。info.prop 至少有 name、author、version，process 仅支持 main。总开关、启动自动运行和新插件开关默认关闭。Agent 提交的新插件保持关闭，由用户手动开启。

首次执行顶层语句后调用可选 onLoad()；卸载调用可选 onUnload()；插件设置按钮调用 openSettings()。支持 useOnLoad(String methodName)、useOnUnload(String methodName)、useOpenSettings(String methodName)，或 useCallback(String callbackName,String methodName) 绑定这三个回调别名。

根目录增加/删除插件时自动刷新列表；根目录内 main.java、info.prop 或同级 .java/.bsh 文件变化后，对已启用插件合并为 500 ms 后重载。深层子目录变化请手动重载。卸载会解除通过全局 Hook 函数注册的 Hook，取消模块管理的延时、HTTP 与后台任务；已进入不可中断宿主调用的任务只能等其返回，返回后不再交付已取消回调。不要自行创建长期线程。

## 环境变量

- context / hostContext：蜂鸟众包 Application Context。
- classLoader：目标宿主加载器。
- fn / apis：同一个 h.Hchat.host.FengniaoApis 实例。
- pluginId、pluginName、pluginAuthor、pluginVersion、pluginUpdateTime。
- pluginDir / pluginDirFile、scriptDir / scriptDirFile、cacheDir / cacheDirFile。
- processName、pluginProcess="main"、isMainProcess=true。
- hostVerName、hostVerCode、moduleVer。
- bridge：通用配置、反射与 Hook 桥；wa、http、httpClient：同一个通用任务与 HTTP 工具对象，历史命名不含微信业务能力。
- dexKit：共享 DexKit 脚本桥；dexFinder 是 dexKit 的别名；dexKitBridge、dexBridgeHolder 仅在宿主初始化提供对应对象时可用。不要自行创建共享之外的分析引擎。

已导入 File、InputStream、Map、List、Set、Member、Method、Constructor、Field、Consumer、Function、Xposed 三个常用类、KavaReflector 和 org.json.JSONObject/JSONArray。

## 蜂鸟接口索引和调用

fn.catalog(String filter)：检索内嵌的 APK 静态接口索引并返回 JSONArray。

fn.call(String descriptor,Object receiver,Object[] args)：按索引中完整 DEX 描述符调用；静态方法 receiver 为 null，实例方法必须传入该类实际有效实例。对象生命周期、参数类型、认证和业务状态仍由原生宿主决定。

fn.catalog(String filter,int offset,int limit)：分页检索已识别请求注解的方法，默认最多 100 项，单页最多 1000 项。

fn.searchMethods(String filter,int offset,int limit)：后台流式检索全部 DEX 已声明方法（含第三方库），单页最多 500 项。

fn.metadata()：返回目标版本、APK SHA-256 和静态索引数量。fn.mistCatalog(String filter,int offset,int limit)：后台检索 Mist 内的路径/操作名引用，单页最多 500 项。这些引用保留资源位置与上下文，不能单凭路径猜参数。

fn.service(String urlKey,String interfaceName,Object scheduler)：通过 10.11.2 已确认的 f.d().l 原生网络工厂创建 Service 代理；调用方必须确认 urlKey 和 scheduler。仅在目标服务原本允许默认调度器时传 null；不能把这条工厂链套用到所有服务。不发出请求。

fn.request(String urlKey,String descriptor,Object[] args)：创建对应 Service 并调用精确描述符，返回原生 Call 或 Observable；不自动执行或重试。

fn.execute(Object nativeCall)：在后台执行 xc0.b 原生 Call，返回 android.util.Pair。不同接口可能使用其它异步类型，不能一律传入。

fn.hostJson(String text)：创建宿主 ClassLoader 的 fastjson JSONObject，避免模块 org.json/fastjson2 对象与宿主 fastjson1 类型混用。

fn.rpc(String operationType,Object payload)：目前明确抛出 UnsupportedOperationException。没有验证单个通用 OperationType 发包接口，不能假装支持；应使用具体原生 Service。

sendPizza(String json,Consumer success,Consumer failure)：插件生命周期管理的原生异步网络桥，可从脚本线程调用，内部放后台执行。通过静态确认的 q0.d 使用宿主网络栈。json 顶层必须含 data 对象，data.method 只接受 GET/POST/PUT，data.urlPath 必须为已核实路径，streamMode 只能为 0。成功回调参数为宿主 fastjson1 结果对象，失败为宿主错误对象或 Throwable；成功回调也必须检查业务状态。两者只交付一次，在后台解释器锁内执行。没有自动重试。

请求字段和含义见 docs/HOST_ADAPTER.md；认证、签名和登录态由宿主处理。卸载插件会释放脚本回调，但底层请求没有已核验的取消句柄，仍可能继续执行。等待超时按 connectTimeout + readTimeout + 10 秒计算，默认 60 秒，限制在 5～300 秒；超时仅停止等待和回调持有，不保证网络或业务取消。

底层 fn.sendPizza(String json,Consumer success,Consumer failure) 返回可 close() 的 NativeRequest，要求后台调用；直接使用时由调用者管理关闭。插件优先用上述全局 sendPizza，确保卸载清理。

RPC 参数格式、已确认桥接描述符、目录字段与覆盖范围以宿主适配层文档为准。APK 静态扫描覆盖到的方法不等于远程服务允许调用；服务端、动态下载、反射生成和运行时签名语义不能靠单个 APK 证明全部接口可用。

示例：void onLoad() { log(fn.catalog("setting").toString()); }

## 日志、任务、HTTP

- void log(Object text)：写宿主日志与当前插件 log.txt，超过 2 MiB 清空轮转。
- void toast(Object text)：切换主线程提示。
- void async(Runnable action)：插件生命周期管理的后台任务。
- void delay(long millis,Runnable action)：后台延时执行，每插件最多 128 项、所有插件合计 512 项。
- String http.get(String url) / get(String url,Map headers)。
- String http.post(String url,String jsonBody) / post(String url,String jsonBody,Map headers)。
- String http.request(String method,String url,String body,Map headers)。
- void http.requestAsync(String method,String url,String body,Map headers,Consumer success,Consumer failure)。

同步 HTTP 禁止在主线程调用，非 2xx 状态抛出异常；默认总超时 30 秒。requestAsync 成功参数为响应正文字符串，失败参数为错误字符串，均在后台线程运行。通用 HTTP 不自动附加宿主凭据或 RPC 签名，使用原生业务接口请走已核验宿主桥接。不要记录令牌或个人敏感资料。

## 配置与文件

以下每组配置函数保存在当前插件 config.prop，锁位于宿主私有配置目录：

- String getString(String key,String fallback) / void putString(String key,String value)。
- boolean getBoolean(String key,boolean fallback) / void putBoolean(String key,boolean value)。
- int getInt(String key,int fallback) / void putInt(String key,int value)。
- long getLong(String key,long fallback) / void putLong(String key,long value)。
- float getFloat(String key,float fallback) / void putFloat(String key,float value)。
- Set getStringSet(String key,Set fallback) / void putStringSet(String key,Set value)。
- String readFile(String relativePath) / void writeFile(String relativePath,String text)：按 UTF-8 读写，父目录须先创建。

完整文件操作也可使用 Java 标准文件 API。脚本本身拥有宿主进程权限，插件目录不是权限沙箱。

## Hook、反射、DexKit

- Class findClass(String className)。
- Object findClassList(Object strings) / Object findMemberList(Object strings)。
- Method firstMethod(Object target,String name) / firstMethod(Object target,String name,int parameterCount)。
- Constructor firstConstructor(Object target,int parameterCount)。
- Field firstField(Object target,String name)。
- Object invokeMethod(Object target,String name)。
- Object invokeMethod(Object target,String name,Object[] args)。
- Object invokeMethod(Object target,String name,int parameterCount,Object[] args)。
- Object createInstance(Object target,int parameterCount) / createInstance(Object target,int parameterCount,Object[] args)。
- Object getField(Object target,String name) / void setField(Object target,String name,Object value)。
- Object hookBefore(Member member,Consumer callback) / hookAfter(Member member,Consumer callback)。
- Object hookReplace(Member member,Function callback)。
- void unhook(Object handle)。

反射 target 可传对象或 SomeClass.class，Class 用于静态方法和构造器。优先使用完整签名证据；firstMethod 只按名字与参数数量查找，同参数数量重载须再核对类型，不得猜测首项。

Hook 回调参数是 XC_MethodHook.MethodHookParam。before/after 用 Consumer.accept(Object param)；replace 用 Function.apply(Object param) 返回替换结果。Hook 在宿主原线程触发，禁止阻塞 UI。通过模块全局函数创建的 Hook 会在卸载时释放。

findClassList/findMemberList 支持字符串、列表和数组；多个字符串先同时匹配，零命中才逐个扩展；同类展开的其它成员不是直接命中证据。内置界面文字优先查资源，目标版本证据以 DexClub 导出为准。

## 脚本与 DEX 加载

- void reloadPlugin()：异步重载当前插件。
- void eval(String code) / void loadJava(String path)。
- ClassLoader loadDex(String path)。
- String compileSnapshot(String path)。
- Object evalSnapshot(String path) / evalSnapshot(InputStream input) / evalSnapshot(byte[] data)。

相对路径按插件目录解析。DEX/JAR/APK 会复制到宿主私有 codeCache，以内容摘要命名并设为只读后加载。快照 .bshs 使用上游兼容格式和固定密钥，不保证脚本机密性。本次提取不包含音频编解码、微信菜单或微信业务回调。
