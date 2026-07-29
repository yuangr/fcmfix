# fcmfix (Android 10-16)

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

让 FCM/GCM 唤醒未启动的应用进行发送通知。

### 核心功能

- 修复 `Failed to broadcast to stopped app` — FCM 广播无法唤醒已停止应用的问题
- 阻止 Android 系统在应用停止时自动移除通知栏的通知
- 在 MIUI/HyperOS / OxygenOS / ColorOS 上动态解除来自 FCM 的自启动限制
- 移除 MIUI/HyperOS 对后台应用的通知限制
- GMS 进程内强制推送修复 — 欺骗 GMS 认为目标应用正在运行
- GMS 长连接心跳保活 — 防止长连接因间隔过长断开

### 支持的 ROM

| ROM | 支持版本 | 备注 |
|-----|---------|------|
| **ColorOS / OxygenOS** | 15 / 16 | 主要适配目标 |
| **MIUI / HyperOS** | 12 / 13 / HyperOS | 需勾选”电量和性能”作用域 |
| **原生 Android** | 10 - 16 | 可能需要手动授予自启动权限 |

### v1.7 — 安全加固与稳定性修复

- **安全加固**: 内部广播接收器添加 `signature` 级自定义权限保护，防止第三方应用注入
- **PowerkeeperFix**: 修复构造函数 Hook 在 MIUI 上必现的 NPE 崩溃
- **IceBox 集成**: 修复 `assert` 无效导致 NPE、`UserHandle` 获取方式错误（多用户支持），线程池化解冻任务 + 去重竞态保护
- **AutoStartFix**: 恢复 `isFCMIntent()` 守卫，仅对 FCM 广播放行；硬编码参数索引替换为安全的动态提取
- **BroadcastFix**: 移除 `extractTargetPackage` 中不安全的 extras/args 启发式包名扫描；启用 `BroadcastSkipPolicy` Hook
- **PowerkeeperFix**: 对系统集合并发修改添加 `synchronized` 保护 + null 检查
- **ReconnectManagerFix**: `Timer` → `ScheduledExecutorService` 单例，消除线程泄漏
- **OplusProxyFix**: WakeLock / HansManager 实例捕获分离，增加 `getInstance()` + 静态字段多路径回退解冻；volatile 修饰静态状态字段
- **XposedModule**: `ArrayList` → `CopyOnWriteArrayList`、`HashMap` → `ConcurrentHashMap`；修复双重 logcat 输出；增强 FCM Intent 识别
- **新增**: `DiagnosticsLogger` — 启动时诊断系统信息与关键类存在性；`GmsForcePushFix` — GMS 进程内强制推送

### LSPosed 作用域

| 作用域 | 包名 | 说明 |
|--------|------|------|
| 系统框架 | `android` | 核心 Hook（必选） |
| Google Play Services | `com.google.android.gms` | GMS 推送修复 |
| 电量和性能 | `com.miui.powerkeeper` | MIUI 设备专用，推送无问题可不勾选 |

### 关于 FCM

FCM 是 Google 维护的一条介于 Google 服务器与 GMS 应用之间的推送长连接。

工作流程：应用服务器 → Google 服务器 → GMS 应用 → 广播 → 目标应用 → 通知。

当目标应用处于非运行状态时，系统会拒绝广播，报错 `Failed to broadcast to stopped app`，fcmfix 主要解决这个问题。

### 已知问题

- 非 MIUI/HyperOS/OxygenOS/ColorOS 系统可能需要手动授予目标应用自启动权限，以及电池优化设为”不优化”
- GMS 版本更新后，`ReconnectManagerFix` 的心跳 Hook 点可能需要重新探测

### 鸣谢

本项目基础功能与底层唤醒逻辑基于 **[kooritea/fcmfix](https://github.com/kooritea/fcmfix)**。感谢原作者的杰出工作！

本仓库在此基础上持续维护，针对 ColorOS 16 等现代系统进行兼容性适配与安全加固。
