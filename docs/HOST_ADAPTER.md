# 蜂鸟众包 10.11.2 宿主适配

本文件说明模块已实现的桥，不把静态证据当成实机运行结果。目标包、版本与 APK SHA-256 见 README；启动后台校验通过后才安装入口和运行时。

## 设置 Hook

精确类 me.ele.lpd.dynamiclib.magex.HBCommonMagexActivity，方法 onCreate(android.os.Bundle)。只在 Intent scene_name 等于 hb-settings 时，在 android.R.id.content 中包装原始根视图并添加 52dp 入口。使用 View tag 防止重复挂载，没有保存持有 Activity 的全局 View。失败时尝试恢复原始根视图、顺序和参数。

资源确认该页是 ConstraintLayout 包含 FrameLayout/container_layout，实际设置主体来自 Mist。详见 reverse/evidence。待设备检查系统栏、键盘、旋转重建、返回导航、字体缩放和动态模板。旧路由 eleme-lpd://settings 与团队页面尚不支持。

## 目录字段

host-api-catalog.json 为网络方法，host-methods.jsonl.gz 为全 DEX 已声明方法。每条记录含 descriptor、owner、name、parameters、returnType、accessFlags、interface、native、sourceEntry；存在注解时包含 annotations/parameterAnnotations。注解里的类型名保持 DEX 原样，部分非字符串值保持 encodedType/raw。索引也包含构造器，fn.resolve/fn.call 只解析普通 Method；构造器应通过已确认参数类型和 KavaReflector 另行创建。

dexclub-network-evidence.json 是独立定位的 338 个描述符及方法注解。mist-endpoint-references.json 是资源路径与上下文，不是独立可调用 Service。host-profile.json 保存逆向入口配置。

## 精确调用与原生 Service

fn.call 使用完整 DEX 方法描述符、真实 receiver 与 Object[]，核对参数数量、实例类型和返回类型，不吞掉底层调用异常。primitive/数组由描述符解析。实例、参数、线程和生命周期仍需由调用方确认。

fn.service 使用已确认的 f.d().l(urlKey, interfaceClass, rx.f scheduler)。fn.request 在此代理上调用一个描述符，scheduler 默认为 null，仅适用于已核实允许默认调度器的服务。不同服务可能有自己的 setHost/工厂覆盖；不能假定全部 338 个方法都能走这条链。已有真实 Service 实例时用 fn.call。fn.execute 仅针对 xc0.b.execute()，返回 android.util.Pair，必须自行核对业务结果。RxJava Observable 等其它返回类型需按真实类型处理。

## sendPizza

已确认 q0 的无参构造器与方法：

    Lme/ele/lpd/dynamiclib/magex/adapter/q0;->d(Lme/ele/android/lmagex/h;Ljava/lang/Object;Ljava/util/Map;Lsa0/c$a;)Ljava/lang/Object;

桥将 JSON 文本通过宿主 fastjson1 解析为 JSONObject，传给第二参数；首参数 h 为 null、Map 为空；Proxy 实现 sa0.c$a 的 a/b 成败回调。证据表明原方法异步返回 null。模块只接收一次响应，拒绝流式 streamMode。

输入结构为顶层对象包含 data 子对象。data 字段：

| 字段 | 已确认用途与限制 |
| --- | --- |
| method | 仅大写 GET、POST、PUT |
| urlPath | 已核实的具体请求路径，不能为空 |
| host、bizName | 走宿主既有环境路由；不要猜 URL key |
| bizParam | GET 查询参数或 POST/PUT 请求数据；准确类型需按具体调用确认 |
| connectTimeout、readTimeout | 宿主超时参数；模块另设回调等待上限 |
| callbackOnThread | 宿主回调线程配置；脚本回调统一转后台解释器锁 |
| streamMode | 仅 0；原生流式多次回调未包装 |
| ext_headers、contentType、upload | 原生适配器识别的扩展字段，需按具体证据使用 |
| xtopParam、xtopMeta | 原生 xtop 包装参数，不能按普通 JSON 接口猜测 |

宿主 bizName 映射：knight → key_hb_order；httppizza → PIZZA；talaris → host；aiot → AIOT。成功对象已确认可能含 data、requestId、errorCode、errorMsg、statusCode、xtopCode、responseType。回调成功并不代表业务成功。

全局 sendPizza 为插件管理资源，优先使用它；fn.sendPizza 是底层句柄。取消或超时只释放回调，底层业务请求仍可能完成。不能通过“停止”来保证已提交业务操作被撤回。此次未发出任何业务请求。
