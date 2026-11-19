# 来源与第三方声明

本工程从用户指定的 Hchat-alt-entry 源码快照中提取脚本插件、Agent、Codex 和终端所需代码，并增加蜂鸟众包宿主适配。

- 上游：https://github.com/Hchat-dev/Hchat-alt-entry
- 基线提交：f4b52ae3a4a519c71facda977092516ff755b831
- 上游基线未提供根目录 LICENSE；本工程不对上游代码另行授予许可。公开发布或再分发前需确认作者授权。
- BeanShell、Termux、DexKit、Jadx、ARSCLib、FastKV、KavaRef、OkHttp、fastjson2 等代码和库继续适用各自许可，保留原有文件头。
- Termux 相关 AAR 与源代码来自上述基线，按其 GPLv3 及相应源码要求处理。
- 目标 APK 不包含在此工程内。接口目录为用户提供 APK 的静态元数据，不包含用户账户、会话令牌或业务响应。
