# 交付验证记录

日期：2026-11-19。上游基线：f4b52ae3a4a519c71facda977092516ff755b831。以下检查在 Linux ARM64 容器中进行，没有加载目标 APK 执行业务代码。

## 已通过

| 检查 | 结果与范围 |
| --- | --- |
| APK 指纹 | 原始输入直接计算 SHA-256，与 metadata/profile/README/运行时代码一致 |
| DEX 目录 | 7 个顶层 DEX，368,327 个已声明方法；逐条描述符与结构字段一致 |
| 方法分类 | 接口方法 18,526；native 方法 3,938；数量包含第三方库 |
| 网络注解交叉比对 | 338 个描述符与 DexClub 独立提取集合完全相等；268 个包含参数注解 |
| 关键入口覆盖 | 设置 onCreate、sendPizza、网络工厂、原生 execute、host JSON 解析均在完整 DEX 目录中 |
| Mist 可复现提取 | 206 包、1,880 文本成员、693 处引用、253 个不同值，0 错误；与原独立提取引用逐条一致 |
| Kotlin 源码 | 61 个生产 Kotlin 文件由 Kotlin 2.4.20 直接编译通过；保留上游及 Android API 警告 |
| Java 源码 | 152 个生产 Java 文件由 ECJ 3.43 / Java 21 直接编译通过，包含 BeanShell 与 Kotlin/Java 互调依赖 |
| BeanShell 语法 | 当前完整运行时前导、示例插件、生命周期、异步 HTTP 与 Hook 回调片段解析通过；不执行片段里的请求和 Hook |
| 描述符模拟测试 | 基本类型、数组、多维数组、对象、void、重载、错误返回类型和非法描述符处理通过 |
| 回调模拟测试 | 100 轮并发成败竞争每轮只交付一次；close 后迟到回调不交付 |
| 文件一致性 | JSON/XML 可读；文档版与 APK 内嵌 SCRIPT_PLUGIN_API.md 一致；无 APK、密钥、local.properties 或编译缓存混入交付源码 |

描述符与回调测试使用自建内存夹具，配合 KavaRef JVM 运行支持，不等于 Android/Xposed 的行为验证。该测试发现提取工程漏了 KavaRef Android 运行支持，已在 Gradle 增加 kavaref-android 1.1.0。

## 源码审查修正

- 设置 Hook 使用逆向确认的 HBCommonMagexActivity 与精确 hb-settings 场景；移除会间接保留 Activity 的 View 映射，挂载异常时恢复原始内容。
- 通过 Xposed initZygote 记录模块真实 APK 路径，供 native 库、终端与内嵌资源读取使用，避免只靠应用目录猜测。
- 开始安装运行时前在后台校验 APK 指纹。版本相同但内容不同仍拒绝。
- Agent 关闭/停止或异常不再静默删除已有暂存修改；中断末尾保存恢复点，重开会话读取尚在持久化的最新状态，同一会话不能并发打开。
- 原生 sendPizza 限定单次响应；插件卸载清理脚本回调。底层没有已确认的取消入口，不能承诺业务撤销。
- 校正了逆向报告中混用的会话目标标识，统一为实际 APK SHA-256。

以上 UI、恢复和宿主调用修正主要完成了源码审查及编译检查，仍须设备回归。

## 检查环境限制

直接编译使用 Android 15 的 android-all API 类库以及手工准备的依赖，过滤了与 JDK 重复的 java/javax 类。部分逆向依赖来自预装 DexClub，例如 ARSCLib 1.4.0、Jadx 1.5.6，与 Gradle 声明的 ARSCLib 1.3.8、Jadx 1.5.5 不完全相同。没有运行 AGP、AAPT2、D8、R8、Manifest 合并或完整依赖解析，不能据此保证 Gradle 构建成功。

遵循上游 AGENTS.md 的“用户明确允许本地构建后才运行 Gradle”约定，此次没有本地 Gradle 构建，也没有上传代码或触发远程 CI。交付的是源码工程和可手动触发的 CI 配置，没有 APK。

## Git 检查

已检查 git status --short、git diff --check、git diff --stat。新工程文件均为新增，未创建提交。完整 diff --check 的警告全部来自原样复制的 BeanShell 文件（尾部空白/文件尾空行），148 个 BeanShell 文件与上游逐字节一致；排除该原样第三方目录后 diff --check 通过。没有为了消除这些警告改写原解释器。

## 尚需完成的设备验收

1. 完整 Android 构建、签名和安装；确认最终产物的作用域、ABI、native 库和反射保留规则。
2. LSPosed 中加载 10.11.2 原始目标，验证设置入口、首次初始化、返回导航、旋转、系统栏和字体缩放。旧路由与团队设置尚未适配。
3. 使用目录示例测试插件加载、重载、开关、卸载、异常日志与回调清理。
4. 用自己的模型配置测试 Agent 生成、审阅、提交、中断/关闭后恢复以及基线冲突拒绝。
5. 验证下载服务、Ubuntu 安装、Termux native 加载、Codex CLI/网页版与停止清理。
6. 仅对已核实的具体请求逐项检查参数、路由、线程、登录态和业务返回；此次没有发业务请求，不宣称全部服务器接口可用。
