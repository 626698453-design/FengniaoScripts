# 蜂鸟众包 10.11.2 静态逆向结果

分析日期：2026-11-19。分析对象为用户提供的 APK，未连接账号、未发出业务请求，也未在手机上完成运行验证。以下类名、方法、字段和资源均来自该 APK 的 DexClub 反编译或原始资源。

## 版本边界

- 包名：`me.ele.crowdsource`
- versionName：`10.11.2`；versionCode：`1247`
- SHA-256：`1c3540bd1446133d4161a66750b8475c41633da3e4f4c4037aaf3b05e48d18a5`
- Application：`me.ele.hb.launch.crowd.CrowdApplicationContext`
- 机器可读接入参数：`host-profile.json`。

## 设置入口 Hook 点

此版本默认设置页不是一个独立 SettingsActivity，而是通用动态页面：

```text
me.ele.lpd.dynamiclib.magex.HBCommonMagexActivity
Lme/ele/lpd/dynamiclib/magex/HBCommonMagexActivity;->onCreate(Landroid/os/Bundle;)V
```

`onCreate()` 调用 `replaceFragment()`。后者从 Intent 的 `scene_name` 字符串读取场景，`mist_page` 默认为 true，然后替换内容 Fragment。普通众包设置的精确场景值是 `hb-settings`。不要以类名含 setting 或场景含 setting 的模糊判断对其他动态业务页面插入入口。

原始证据：

- `evidence/settings-onCreate.java.txt`
- `evidence/settings-replaceFragment.java.txt`
- `assets/hb-settings.mistp` 内的 `hb-settings/HBUserCenterNavigationBar.mist`，另保存为 `evidence/settings-navigation.mist.json`。

导航资源读取 Orange 配置 `hb_lmagex/enable_mist_setting`（默认 true），组合：

```text
lpd://magex?scene_name=hb-settings&mist_page=true&utpage=page_settings
```

团队分支会追加 `-team`；本次确认了该路由引用，未获得对应团队资产的渲染证据。若远程配置将动态设置关闭，资源引用的旧路由为 `eleme-lpd://settings`，此旧路由的入口尚未验证，不能承诺覆盖所有远程配置。

### 原生容器布局

`getLayoutId()` 返回 `ff0.m.v1 = 0x7f0c00b6`，资源解析为 `res/layout/dy_view_common_lmagex.xml`。它是全屏 ConstraintLayout，内部唯一全屏 FrameLayout `@id/container_layout`。`replaceFragment()` 的 `ff0.k.G4 = 0x7f0901d3` 也解析为同一 `container_layout`。证据见 `evidence/settings-getLayoutId.java.txt`、`evidence/settings-resources.json` 与 `evidence/dy_view_common_lmagex.xml`。

建议在上述精确页面的 `onCreate` 完成后，为独立入口预留原生布局空间；不要把可点击悬浮行覆盖到原有设置项上。若重新包装已有内容根，保留原有视图 ID、布局参数、Fragment 容器和已有页面状态。手机上仍需检查状态栏、刘海、返回键、旋转/重建和远程动态模板变化。

`hb-settings/index.mist`（保存为 `evidence/settings-index.mist.json`）使用 `HBUserCenterNavigationBar`，标题“设置”、id `setting-topbar`，主体卡 `SettingView`、id `setting_view`。主体不是 APK 固定 XML 的普通 Android 设置列表。

## 供插件复用的宿主网络桥

确认宿主已有动态 API 桥 `sendPizza`：

```text
class: me.ele.lpd.dynamiclib.magex.adapter.q0  (classes6.dex)
no-arg constructor: <init>()V
method:
Lme/ele/lpd/dynamiclib/magex/adapter/q0;->d(
  Lme/ele/android/lmagex/h;
  Ljava/lang/Object;
  Ljava/util/Map;
  Lsa0/c$a;
)Ljava/lang/Object;
```

`getName()` 返回 `sendPizza`。第二个参数必须是宿主 ClassLoader 下的 `com.alibaba.fastjson.JSONObject`，不是 fastjson2，也不是模块自己带的同名类。它从该对象的 `data` 子对象读取参数。第一个 h 参数只被带回回调，当前方法没有直接解引用；可为空。第三个 Map 同样传回回调。方法异步返回 null。完整方法证据见 `evidence/sendPizza-q0.java.txt`。

回调 `sa0.c$a` 经反编译确认是 interface，可通过宿主 ClassLoader 的动态 Proxy 实现：

```text
a(Lme/ele/android/lmagex/h;Ljava/lang/Object;Ljava/util/Map;)V   成功
b(Lme/ele/android/lmagex/h;Ljava/lang/Object;Ljava/util/Map;)V   失败
```

回调与实现证据见 `evidence/sendPizza-callback.java.txt`、`evidence/sendPizza-callback-impl.java.txt`。streamMode=1 时成功回调可能多次触发，并最终收到 trailer，不能简单按第一次成功即销毁回调。

输入字段：`host`、`urlPath`、`method`、`bizName`、`bizParam`、`connectTimeout`、`readTimeout`、`callbackOnThread`、`upload`、`ext_headers`、`contentType`、`streamMode`、`xtopParam`、`xtopMeta`。普通 `GET` 使用查询参数；`POST`/`PUT` 使用请求体；xtop 路径交给宿主 xtop 包装器处理。建议上层仅接受标准大写方法值；该版本内部对大小写的判断不完全一致。

当不指定 host 时，它按 bizName 使用宿主环境映射：knight → key_hb_order，httppizza → PIZZA，talaris → host，aiot → AIOT。没有必要重新拼接域名或提取 token、签名。

返回 JSONObject 的已确认字段：`data`、`requestId`、`errorCode`、`errorMsg`、`statusCode`；部分请求另有 `xtopCode`、`responseType`。失败可能是异常对象或错误 JSONObject，上层必须兼容。网络桥复用了宿主 platform 网络栈；其实际登录状态、服务权限和业务校验仍由宿主/服务器决定。

## 原生注解接口服务代理

另一条接入链：

```text
Lme/ele/lpdfoundation/network/d;->setHost()V
    this.mService = f.d().l(getUrlKey(), getHttpServiceClass(), getNetworkScheduler())

Lme/ele/lpdfoundation/network/f;->d()Lme/ele/lpdfoundation/network/f;
Lme/ele/lpdfoundation/network/f;->l(Ljava/lang/String;Ljava/lang/Class;Lrx/f;)Ljava/lang/Object;
    return e(urlKey, scheduler).e(serviceInterface)

Lxc0/t;->e(Ljava/lang/Class;)Ljava/lang/Object;
    Proxy.newProxyInstance(interfaceLoader, new Class[]{serviceInterface}, handler)
```

证据见 `evidence/network-*.java.txt`。模块可以在准确工厂方法返回后记录接口 Class 和原生代理，按照索引中的精确 descriptor 调用已捕获的实例。如果宿主尚未创建该服务则返回“服务未就绪”；不要猜 URL key、scheduler 或自行制造认证参数。返回值可能为 RxJava Observable 或其他宿主异步类型，上层必须显式处理；反射成功不等于业务成功。

## 静态接口索引与覆盖边界

`annotated-network-apis.json`：完整分页查找以下三组方法级注解，并逐个 inspect_method 读取注解，共 **338** 个方法，338 次检查均成功：

| 注解族 | 方法数 |
| --- | ---: |
| me.ele.android.network.http | 283 |
| retrofit2.http | 16 |
| com.alipay.mobile.framework.service.annotation.OperationType | 39 |

每项含精确 descriptor、dex 来源、注解族、方法注解和存在时的泛型签名。选用的 inspect 接口不提供参数注解，因此这里没有把位置参数自动映射为 Query/Body/Field。不可据此自动猜参数名。

`mist-endpoint-references.json`：扫描 APK 全部 **206** 个 `.mistp` 压缩包的 **1880** 个文本成员，提取 `urlPath`/`api`/`apiName`/`operationType` 的字面量引用，获得 **693** 处引用、**253** 个不同值，解压错误 0。每项含资产路径、成员、键、值和原文上下文。可用 `extract_mist.py` 复现。

这些是本 APK 静态可发现的接口目录，不能等同于“全部后端接口”。运行时计算字符串、服务器下发模板、热修复、原生协议和未使用这些注解的网络实现可能不在其中。接口的写入副作用、参数业务含义、服务器授权与兼容性也不能仅靠路径名判断。

## 验证状态

- 已完成 APK 指纹、目标包/版本、设置容器、资源 ID、精确场景、网络桥和回调签名的静态确认。
- 未进行设备运行验证；未发出业务请求；未提取账号凭证。
- 后续版本应重新校验签名和配置，未知版本应明确提示不匹配，不能把本版本的混淆名假定为跨版本 API。
