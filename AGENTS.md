# 蜂鸟脚本工程协作规则

- 本工程只有脚本插件、插件 Agent、Codex/Ubuntu/终端与宿主适配。不要重新引入微信业务功能。
- 先读 README.md、docs/SCRIPT_PLUGIN_API.md、docs/HOST_ADAPTER.md；涉及宿主内部实现时核对 docs/reverse 的原始证据和当前 APK。
- 目标目前只有 me.ele.crowdsource 10.11.2 / 1247 / ARM64 主进程，SHA-256 必须匹配 README。不要猜类名、签名、Intent、参数或路径。
- 反射统一使用 KavaReflector，模块错误统一 HLog.e。不要吞掉原始 Throwable。
- 保留事务工作区、Hook/异步资源清理和恢复点校验；网络失败、窗口关闭或停止不能静默丢失暂存修改。
- 修改接口、路径、构建或设置入口后同步文档与内嵌指南。
- 除非用户明确允许本地构建，不运行 Gradle；默认交给手动触发的 CI。直接源码检查不能当成 APK 打包或实机通过。
- 不加入 APK、keystore、local.properties、缓存、API Key、Token 或个人数据。
- 完成前检查 git status --short、git diff --check、git diff --stat，并如实报告验证边界。
