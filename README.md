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

### lsposed作用域
- 在miui/hyperos上如果推送没有问题，就不需要勾选电量和性能

### 关于fcm

fcm是在Android中由google维护的一条介于google服务器与gms应用之间用于推送通知的长链接。  
一般的工作流程为应用服务器将消息发送到google服务器，google服务器将消息推送给gms应用，gms应用通过广播传递给应用，应用通过接收到的fcm消息决定是否发送通知和通知内容。  
其中gms通过fcm广播通知应用时，如果应用处于非运行状态，就会出现`Failed to broadcast to stopped app`，fcmfix主要就是解决这个问题。

### 已知问题

- 非miui/hyperos/OxygenOS15/ColorOS15系统可能需要给予目标应用类似允许自启动的权限，以及电池选项设置为不优化。
