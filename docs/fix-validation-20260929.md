# fcmfix v1.9 修复与验证记录（2026-09-29）

## 源码与构建方式

- 基线：`744c9c28d8169292925c6c4469c29ea3bb16064f`，v1.8。
- 修复分支：`codex/fcmfix-review-fixes`。
- 使用 GitHub Actions 执行 `testDebugUnitTest assembleRelease`，不在本机继续编译。
- 本次下载的本机 JDK 17、Android SDK、Gradle 和项目构建缓存已删除；原有 Java 等软件未改动。
- 版本：versionCode 190，versionName v1.9。

## 主要问题对应的修复

| 审查项 | 修复 | 回归验证 |
| --- | --- | --- |
| F1 广播安全检查整体跳过 | 保留原始 BroadcastSkipPolicy；按真实 GMS UID、显式目标、允许列表授权，仅调整 stopped 标志和厂商解冻；本应用 Firebase 服务转发仅允许本 UID | BroadcastFixTest：原策略返回的权限拒绝仍保留；伪造 extras、系统 UID、未知目标均不能放行；record UID 优先于线程作用域 |
| F2 Hook 回调重复 n² 次 | 每个拦截器仅执行本注册回调；ReconnectManagerFix 按 Executable 幂等注册 | XposedBridgeTest：三注册各一次、原方法一次、顺序、提前返回、异常、unhook、注册失败；ReconnectManagerFixTest：多次启动仅一注册 |
| F3 解冻期间后续消息丢失 | 仅合并解冻，按 userId:package 为每条广播排队；完整参数和原调用身份回放；使用 int 成功返回；容量拒绝保留原始调用 | PendingBroadcastsTest：突发三条逐条有序、投递中新到消息、用户隔离、满队列、执行器拒绝、失败继续、激活失败逐条报告 |
| F4 appOp 猜测误改 userId | 移除 AppOps 参数改写 | BroadcastFixTest：无参数名的 Android 16 签名中 appOp 与 USER_ALL 均保持 -1 |
| F5 watchdog 累积 | 定时器实例只保留一个 Future，取消旧任务并从队列移除；任务代次校验，服务销毁清理 | LatestTaskSchedulerTest：1000 次重设只保留一队列项、旧任务不执行、对象身份隔离与全部取消 |
| F6 更新配置丢失与 Hook 等待 | dirty 合并重载，读取期间更新会补读；不可变快照一次发布，移除 join 等待 | CoalescingReloaderTest：阻塞读期间更新不丢失、失败和执行器拒绝可恢复；ConfigSnapshotTest：不可变和异常类型 |
| F7 CI 分支错误 | 监听 main、codex/**、PR 和手动运行；先测试再打包 | GitHub Actions 远程执行 |
| F8 测试被忽略 | 移除 test/androidTest 忽略规则，新增并跟踪测试 | Git 跟踪 13 个测试及 fixture 文件、32 项测试方法 |

## 边界

- 此次是源码和 JVM 回归验证，尚未在 ColorOS / Vector 手机上安装或做息屏推送实测。
- IceBox 异步重放仅用于可自行获取服务锁并重新检查权限的 `broadcastIntentWithFeature` Binder 入口；旧系统私有 `broadcastIntentLocked` 保留原始调用，不能保证唤醒已禁用应用。
- 解冻失败会逐条记录失败；不会把不同消息当作同一个解冻任务而丢弃。
- 工作资料 / 分身解冻改为使用目标 userId，尚未验证这些厂商场景。没有改变 Doze 策略，也没有声称测得续航增益。
- 仓库未配置签名 secrets，默认 Release 产物未签名；覆盖安装需要与原版相同的签名密钥。
- 审查报告“其他需要改进”中的 UI 检测准确性、保存失败状态回滚、Activity 服务监听器生命周期和 GMS 全局 Hook 范围不属于 F1–F8，本次没有宣称完成这些后续改进。

## 构建结果

- [GitHub Actions 成功运行](https://github.com/yuangr/fcmfix/actions/runs/36448447896)
- 构建源码提交：`8bcb7c456ae94dccd8ce0025277cb9d12d693386`。
- 32 项测试全部通过，失败 0、跳过 0；Release APK 已生成。SDK 初始化曾因旧 `tools` 包下架失败，已改为仅安装 platform-tools，并指定命令行工具版本。
- APK：`artifacts/ci-36448447896/unsigned-apk/fcmfix-v1.9-release-unsigned.apk`，6,396,239 字节。
- SHA-256：`e727970db1d640219fae5ff187477517ae3193c6ca0ee21b5c97b6bfc17645cc`。
- 报告：`artifacts/ci-36448447896/test-results/index.html`。
- 已检查 APK 内的 dex 和 Xposed scope 资源包含系统、MIUI Powerkeeper、GMS。APK 未签名，未安装手机。
