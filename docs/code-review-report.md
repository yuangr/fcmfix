# fcmfix 全维度代码审查报告

> **审查日期**: 2026-07-29  
> **项目**: fcmfix (Android Xposed/LSPosed Module)  
> **审查范围**: 19 个 Java 源文件  
> **审查维度**: 代码质量 · 安全漏洞 · 静默失败 · 性能优化  
> **审查引擎**: java-reviewer · security-reviewer · silent-failure-hunter · code-simplifier

---

## 发现总览

| 级别 | 数量 |
|------|------|
| ⬛ CRITICAL | 3 |
| 🟥 HIGH | 8 |
| 🟨 MEDIUM | 13 |
| 🟩 LOW | 10 |
| **总计** | **34** |

---

## ⬛ CRITICAL（3 项）

### C1. `PowerkeeperFix.java:41-86` — 构造函数 Hook 在字段初始化前访问，必现 NPE

```java
// 行 41：覆写 beforeHookedMethod（构造函数体执行前触发）
protected void beforeHookedMethod(MethodHookParam methodHookParam) {
    // 行 59：此时构造函数还没执行，mSystemBlackList 为 null
    List blackList = (List) XposedHelpers.getObjectField(
        methodHookParam.thisObject, "mSystemBlackList");
    blackList.remove("com.google.android.gms");  // ← NPE 必现
```

**原因**: XposedBridge 在 `beforeHookedMethod` 回调完成后才执行 `chain.proceed()`（即实际的构造函数体）。因此所有实例字段都是 JVM 默认值 `null`。

**影响**: MIUI 电量管控（Powerkeeper）绕过**完全失效**。

**修复**: 将 Hook 改为 `afterHookedMethod`。

---

### C2. `ReconnectManagerFix.java:83` — GMS 进程中 Exported 广播接收器，任意应用可注入日志

```java
// 行 83：在 GMS 进程内注册为 RECEIVER_EXPORTED
context.registerReceiver(logBroadcastReceive, intentFilter, Context.RECEIVER_EXPORTED);

// 行 227：任意第三方 app 可发送广播，text 直接传入 GMS 进程的反射调用
XposedHelpers.callStaticMethod(GcmChimeraService, GcmChimeraServiceLogMethodName,
    new Class<?>[]{String.class, Object[].class},
    "[fcmfix] " + intent.getStringExtra("text"), null);
```

**影响**: 设备上任何应用都可以发送 `com.kooritea.fcmfix.log` 广播，注入任意字符串到 GMS 进程的反射调用中。

**修复**: 改用 `RECEIVER_NOT_EXPORTED` 或添加签名级自定义权限。

---

### C3. `XposedModule.java:218` — Exported 配置更新广播，无权限保护

```java
context.registerReceiver(..., updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
```

**影响**: 任何应用可以发送 `com.kooritea.fcmfix.update.config` 广播触发 system_server 中的配置重载。

**修复**: 改用 `RECEIVER_NOT_EXPORTED`，通过 LSPosed 远程 SharedPreferences API 传递配置变更。

---

## 🟥 HIGH（8 项）

### H1. `BroadcastFix.java:242-266` — IceBox 解冻多线程竞态条件

当同一冻结应用收到多条 FCM 消息时，每条都创建新线程解冻 + 30 秒轮询 + `invokeOriginalMethod`。多个线程并发调用 `invokeOriginalMethod` 可能破坏 AMS 内部状态。

**修复**: 添加 `ConcurrentHashMap<String, AtomicBoolean>` 去重，每个包名同时只允许一个解冻线程。改用线程池代替裸线程。

---

### H2. `IceboxUtils.java:41, 61` — `assert` 无效 + UserHandle 获取方式错误

```java
assert bundle != null;                              // 生产环境默认禁用 → NPE
int userHandle = Process.myUserHandle().hashCode(); // hashCode ≠ userId
```

**修复**: 
- `assert` → `if (bundle == null) return false;`
- `hashCode()` → `android.os.UserHandle.myUserId()`

---

### H3. `AutoStartFix.java:94, 136` — 自启动绕过过于宽泛（非 FCM 广播也被放行）

```java
// HyperOS：无 isFCMIntent() 检查，所有广播都放行
if (targetIsAllow(target)) { methodHookParam.setResult(true); }

// ColorOS isAllowStartService：同样问题
if(targetIsAllow(target)) { methodHookParam.setResult(true); }
```

注释掉的 `isFCMIntent()` 检查说明作者知道应该限制范围。

**修复**: 恢复 `isFCMIntent()` 守卫条件。

---

### H4. `BroadcastFix.java:174-206` — `extractTargetPackage` 从 payload 猜测包名

遍历 Intent extras 所有 String 值，第一个匹配 allowList 的即被当作目标包名。消息正文碰巧包含包名会导致错误的目标识别。

**修复**: 仅依赖 `intent.getComponent().getPackageName()` 和 `intent.getPackage()`，移除 extras 和 args 的启发式扫描。

---

### H5. `GmsForcePushFix.java:67, 88` — 系统性篡改 API 返回值，范围过大

```java
appInfo.flags &= ~ApplicationInfo.FLAG_STOPPED; // 对所有 getApplicationInfo 调用生效
param.setResult(false);                           // 对所有 isPackageStopped 调用生效
```

不仅 FCM 路径受影响，GMS 的其他子系统（完整性校验、许可验证等）也被篡改。

**修复**: 通过调用栈检查或更精确的方法重载选择缩小 Hook 范围。

---

### H6. `PowerkeeperFix.java:58-81` — 无同步修改系统服务共享集合

```java
List blackList = (List) XposedHelpers.getObjectField(..., "mSystemBlackList");
blackList.remove("com.google.android.gms");  // 并发修改风险
```

**修复**: 加 `synchronized` 保护 + 每次构造只执行一次的守卫标志。

---

### H7. `ReconnectManagerFix.java:204-216` — 每次触发创建新 Timer 线程

```java
final Timer timer = new Timer("ReconnectManagerFix");  // 每次回调都 new
```

GMS 重连风暴时会导致线程泄漏。

**修复**: 使用共享 `ScheduledExecutorService` 单例。

---

### H8. `AutoStartFix.java:50, 70, 92, 132` — 多处无边界检查的数组索引访问

```java
// 无 args.length 检查
XposedHelpers.getObjectField(methodHookParam.args[2], "intent");
```

OEM ROM 变种可能改变方法参数数量 → `ArrayIndexOutOfBoundsException`。

**修复**: 添加 `args.length > N` 检查。

---

## 🟨 MEDIUM（13 项）

### M1. 25 处空 `catch (Throwable ignored) {}` 块

覆盖 5 个文件：`XposedModule.java`, `AutoStartFix.java`, `BroadcastFix.java`, `OplusProxyFix.java`, `DiagnosticsLogger.java`。

ColorOS/HyperOS 每次更新可能重命名类或改变方法签名，但所有探测式 Hook 失败都完全静默，导致 Hook 失效时开发者无感知。

**修复**: 至少加 `printLog("[WARN] Hook Xxx failed: " + e.getClass().getSimpleName())`

---

### M2. 18 处堆栈丢失 — 仅打印 `e.getMessage()` / `e.toString()`

| 文件 | 行号 | 数量 |
|------|------|------|
| `XposedMain.java` | 25-51 | 7 处 |
| `XposedModule.java` | 56, 90 | 2 处 |
| `MainActivity.java` | 65-493 | 9 处 |

当 message 为 null（如 NPE）时日志输出无意义的 `null`。

**修复**: 
- `Log.e(tag, msg, e)` 三参数版本
- `e.getClass().getName() + ": " + e.getMessage()`

---

### M3. `XposedModule.java:51` — `instances` ArrayList 非线程安全

```java
private static final ArrayList<XposedModule> instances = new ArrayList<>();
```

多线程并发 `add()` → 数据损坏 / `ArrayIndexOutOfBoundsException`。

**修复**: 改用 `CopyOnWriteArrayList`。

---

### M4. `XposedModule.java:184-188` — 配置写-读 happens-before 不完整

```java
// 写端：synchronized (config) { config.put("init", true); }  // monitor-exit 建立 HB
// 读端：config.get("init")  // 无同步！可能看不到其他 key 的最新值
```

**修复**: 使用 `AtomicBoolean` 作为 init 标志。

---

### M5. `OplusProxyFix.java:341-393` — 解冻签名检测失败后无限重试

`s_signatureDetected` 在所有方法签名都失败时不设置，导致每次都重试 4-param 和 3-param。

**修复**: 失败后也设置 `s_signatureDetected = true`。

---

### M6. `PowerkeeperFix.java:40-83` — 每次构造函数调用都 `getDeclaredFields()`

```java
for (Field field : MilletPolicy.getDeclaredFields()) { ... }  // 每次构造都执行
```

字段存在性在运行时不变，应缓存为 `static final boolean`。

**修复**: 在 Hook 设置时一次性检测并缓存。

---

### M7. `XposedUtils.java:29` — `>=` 导致构造器匹配非确定性

```java
if(_matchCount >= matchCount){  // 应是 >
    bestMatch = constructor;     // 同分时选最后一个（迭代顺序不确定）
}
```

**修复**: 改用 `>` 或实现精确类型加权评分。

---

### M8. `KeepNotification.java:36-68` — API 29 崩溃 + 冗余分支

- API 30/31/32/33 设置相同的索引，应合并为范围判断
- API 29（minSdk）没有对应的分支 → `pkg_args_index == 0` → `throw NoSuchMethodError()`

**修复**: 添加 API 29 支持或文档说明不支持。合并冗余分支。

---

### M9. `ReconnectManagerFix.java:184` — `maxField` 可能为 null

```java
long nextConnectionTime = XposedHelpers.getLongField(param.thisObject, finalMaxField.getName());
// finalMaxField 可能为 null（timer 类没有 long 字段时）→ NPE
```

**修复**: 添加 `if (finalMaxField == null) return;`

---

### M10. `XposedModule.java:103, 114` — 双重日志写入

```java
Log.i(TAG, text);              // 第一次 logcat
// ... 非 diagnostics 路径：
XposedBridge.log(text);        // 内部再次 Log.i → 第二次 logcat
```

每条日志在 logcat 中输出**两次**。

**修复**: 移除非 diagnostics 路径下的独立 `Log.i`。

---

### M11. `ReconnectManagerFix.java:156` — Hook `toString()` 在热点路径上

```java
XposedHelpers.findAndHookMethod(timerClazz, "toString", new XC_MethodHook() { ... });
```

`toString()` 被极其频繁调用，每次触发反射 `getObjectFieldByPath` + SharedPreferences 读取。

**修复**: Hook 一个调用频率更低的方法。

---

### M12. `IceboxUtils.java:56` — `isAppEnabled` 异常时返回不安全默认值

```java
} catch (Throwable e) {
    Log.e(TAG, e.getMessage());
}
return true;  // ← 异常时返回 true（假设已启用），安全侧应返回 false
```

**修复**: 异常时返回 `false`。

---

### M13. `KeepNotification.java:69` — Sentinel 值 0 与有效索引 0 冲突

```java
int pkg_args_index = 0;
// ...
if(pkg_args_index == 0 || reason_args_index == 0){ throw new NoSuchMethodError(); }
```

如果未来 API 版本的参数索引恰好是 0，会错误地抛出异常。

**修复**: 使用 `-1` 作为 sentinel。

---

## 🟩 LOW（10 项）

| # | 文件 | 行号 | 问题 |
|---|------|------|------|
| L1 | `XposedModule.java` | 269 | 通知 ID 用 `System.currentTimeMillis()`，同毫秒内冲突覆盖 |
| L2 | `XposedModule.java` | 66 | ContextWrapper Hook 不退订，每次 ContextWrapper 创建都经过 Hook |
| L3 | `KeepNotification.java` | 85 | 魔法数字 `10020`/`10021` 缺少命名常量 |
| L4 | `MainActivity.java` | 206, 275 | 死代码 `containsAll()` 结果丢弃 + 冗余 `size()==0 \|\| isEmpty()` |
| L5 | `BootCompletedReceiver.java` | 8-13 | 空实现死代码，`onReceive` 仅打日志不执行逻辑 |
| L6 | `PowerkeeperFix.java` | 42-43 | 未使用的局部变量 `Field[] declaredFields` + 复制粘贴错误 `super.afterHookedMethod()` |
| L7 | `BroadcastFix.java` | 283-325 | `startHookScheduleResultTo()` 完整实现但从未被调用（构造函数中已注释） |
| L8 | `DiagnosticsLogger.java` | 21-23 | 空的 `onCanReadConfig` override 仅调 super |
| L9 | `XposedUtils.java` | 132-138 | `getObjectFieldByPath` 带 Class 检查的 overload 从未被调用 |
| L10 | `ReconnectManagerFix.java` | 253-255 | `Boolean[] isFinish = {false}` 汉 + raw `Constructor` 类型 |

---

## 修复路线图

| 阶段 | 项目 | 文件 | 工作量 |
|------|------|------|--------|
| **P0 立即** | 构造函数 Hook 改 `afterHookedMethod` | `PowerkeeperFix.java` | 1 行 |
| **P0 立即** | 广播接收器改 `RECEIVER_NOT_EXPORTED` | `ReconnectManagerFix.java`, `XposedModule.java` | 2 处 |
| **P0 立即** | IceBox `myUserId()` + 移除 `assert` | `IceboxUtils.java` | 2 行 |
| **P1 本周** | 恢复 `isFCMIntent()` 检查 | `AutoStartFix.java` | 取消注释 |
| **P1 本周** | `extractTargetPackage` 移除 extras/args 猜测 | `BroadcastFix.java` | ~10 行 |
| **P1 本周** | `instances` → `CopyOnWriteArrayList` | `XposedModule.java` | 1 行 |
| **P1 本周** | IceBox 线程池化 + 去重 | `BroadcastFix.java` | ~15 行 |
| **P2 本迭代** | 25 处 `catch (Throwable ignored)` 加日志 | 多个文件 | 机械化 |
| **P2 本迭代** | 18 处堆栈保留 | 多个文件 | 机械化 |
| **P2 本迭代** | AutoStartFix 数组边界检查 | `AutoStartFix.java` | ~5 处 |
| **P2 本迭代** | KeepNotification sentinel -1 + API29 支持 | `KeepNotification.java` | ~5 行 |
| **P2 本迭代** | 双重日志修复 | `XposedModule.java` | 1 行 |
| **P3 后续** | PowerkeeperFix 字段检测缓存 | `PowerkeeperFix.java` | 中 |
| **P3 后续** | 解冻签名失败修复 | `OplusProxyFix.java` | 小 |
| **P3 后续** | Timer → ScheduledExecutor | `ReconnectManagerFix.java` | 中 |
| **P3 后续** | toString Hook 热点优化 | `ReconnectManagerFix.java` | 中 |
| **P3 后续** | 删除死代码（BootCompletedReceiver 等） | 多个文件 | 小 |

---

## 审查引擎信息

| Agent | 类型 | 发现数 |
|-------|------|--------|
| `ecc:java-reviewer` | 代码质量与 Bug | C1-C3, H1-H7, M1-M9 |
| `ecc:security-reviewer` | 安全漏洞 | C2-C3(s), H1-H6(s) |
| `ecc:silent-failure-hunter` | 静默失败与异常吞噬 | 25 处空 catch, 18 处堆栈丢失 |
| `ecc:code-simplifier` | 性能优化与代码简化 | 22 项 |

---

> **报告生成时间**: 2026-07-29  
> **原始对话**: Claude Code 会话  
> **项目仓库**: [kooritea/fcmfix](https://github.com/kooritea/fcmfix)
