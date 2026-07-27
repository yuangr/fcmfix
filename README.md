# fcmfix(Android 10-15 )

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

让fcm/gcm唤醒未启动的应用进行发送通知  

### 附加功能

- 阻止Android系统在应用停止时自动移除通知栏的通知
- 在miui/hyperos(?)/OxygenOS15(?)/ColorOS15(?)上动态解除来自fcm的自启动限制
- 移除miui/hyperos对后台应用的通知限制
- 没有预期唤醒目标应用时发送提示通知

### 修复与优化 (ColorOS 16 适配优化)

- **修复 ColorOS 16 等系统下更新配置闪退问题**：优化了配置初始化与读取机制，将强制读取 JSON 属性替换为安全的 `optBoolean` 缺省读取模式，并在生命周期入口补充默认值注入，彻底解决启动或重建时因 Xposed 服务连接延迟出现 `JSONException: No value for disableAutoCleanNotification` 的闪退报错。
- **修复重启应用后已勾选应用状态显示为“未选中”的 Bug**：优化了 XposedService 异步状态绑定逻辑，设计并实现了 `updateAllowList` 数据源深度刷新机制，彻底解决因服务异步加载与界面构建顺序竞态导致的已勾选状态在重新打开时丢失的显示异常。
- **修复 OxygenOS 等系统上重启 App 勾选状态无法恢复的问题**：引入本地 SharedPreferences 缓存机制作为配置加载兜底方案。每次保存配置时同时写入本地缓存，App 启动时优先从本地缓存即时恢复勾选状态，不再完全依赖 LSPosed 远程服务的异步绑定，彻底解决因远程服务绑定慢或不可用导致的 UI 状态丢失问题。
- **修复 ColorOS 16 上 FCM 消息经常无法送达的问题**：
  - **OplusProxyBroadcast 动态适配**：将 `shouldProxy` Hook 从硬编码 8 参数改为动态方法发现 + 从 Intent 对象提取参数，兼容 ColorOS 16 方法签名变更。
  - **Hans GMS 限制 Hook 多版本探测**：`registerGmsRestrictObserver`、`updateGmsRestrict`、`isGoogleRestricInfoOn` 现在会探测多个可能的类名，防止 ColorOS 16 类名重构导致 Hook 静默失败。
  - **BroadcastFix API 36 动态参数检测**：`broadcastIntentLocked` 在 Android 16 上增加参数索引自动验证与动态探测回退，避免因方法签名变化导致核心唤醒逻辑失效。
  - **shouldPreventSendReceiverReal 多版本适配**：支持多个类名和参数数量的自动探测，确保自启动拦截绕过在 ColorOS 16 上也能正常工作。
  - **缩短启动等待时间**：将开机后的 Hook 等待时间从 60 秒缩短至 30 秒，减少启动初期 FCM 消息丢失窗口。
- **修复 30 秒启动等待拦截 & 改进 FCM 应用自动识别 (v1.4)**：
  - **彻底移除 30 秒 bootComplete 延迟**：之前 `BroadcastFix` 在系统开机/解锁后会挂起 30 秒（`if (!isBootComplete) return;`），导致在此期间所有 FCM 广播直接被跳过，无法注入 `FLAG_INCLUDE_STOPPED_PACKAGES` 标志。现在改为解锁即生效，零延迟拦截。
  - **多线程配置加载防刷新风暴**：修复 `onUpdateConfig` 因 `HashMap` 跨线程可见性问题引发的并发日志刷屏（15ms 内重复触发 41 次配置读取）。
  - **支持现代 FCM 应用智能识别**：改进 `AppListAdapter` 中的 FCM 组件识别逻辑，不仅识别旧版的 `FirebaseInstanceIdReceiver`，还全面支持 `FirebaseMessagingService`、`GcmReceiver`、`c2dm.intent.RECEIVE` 等现代 Firebase 组件，解决全选 FCM 应用时漏选 Telegram/Gmail/Outlook 等关键应用的问题。
  - **批量配置更新优化**：修正在"全选包含 FCM 的应用"时在循环内重复保存并发送广播的问题，改为单次批量写入，大幅提升性能与稳定性。

### lsposed作用域
- 在miui/hyperos上如果推送没有问题，就不需要勾选电量和性能

### 关于fcm

fcm是在Android中由google维护的一条介于google服务器与gms应用之间用于推送通知的长链接。  
一般的工作流程为应用服务器将消息发送到google服务器，google服务器将消息推送给gms应用，gms应用通过广播传递给应用，应用通过接收到的fcm消息决定是否发送通知和通知内容。  
其中gms通过fcm广播通知应用时，如果应用处于非运行状态，就会出现`Failed to broadcast to stopped app`，fcmfix主要就是解决这个问题。

### 已知问题

- 非miui/hyperos/OxygenOS15/ColorOS15系统可能需要给予目标应用类似允许自启动的权限，以及电池选项设置为不优化。

### 鸣谢与原项目

本项目的基础功能与底层唤醒逻辑基于原作者的开源项目 **[kooritea/fcmfix](https://github.com/kooritea/fcmfix)**。非常感谢原作者的杰出工作与无私开源！

本仓库主要在此基础上，针对 **ColorOS 16** 等现代系统进行了兼容性健壮优化，并修复了部分场景下的异步配置加载闪退及状态丢失等逻辑 Bug。
