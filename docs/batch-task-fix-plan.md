# 批量任务链路修复计划

- 日期：2026-10-31
- 状态：第一批（取消/重试/状态机）已提交 e6ec2c4；第二批（#8/#9/#11/#12/#13）已提交 3db2ca9；第三批（#5 删除暂停/恢复）已实施
- 背景：审阅发现 `BatchTaskService` 的取消/暂停/恢复机制在 UI 层完全不可达，取消与重试功能实际失效

## 一、问题清单（2026-10-31 审阅结论）

| # | 问题 | 根因 | 证据 |
|---|------|------|------|
| 1 | 取消按钮形同虚设 | UI 只调 `TaskQueueManager.markCancelled()`；`BatchTaskService.cancelTask()` 全项目无调用 | `BatchTaskActivity.kt:36-39` |
| 2 | 取消 PENDING 任务后仍被执行 | `markCancelled()` 只改状态，不从 `queue` 移除；`next()` 取出时覆盖回 RUNNING | `TaskQueueManager.kt:81-86,33-42` |
| 3 | 取消 RUNNING 任务后状态被覆盖、FFmpeg 照跑 | 无 job.cancel / FFmpegKit.cancel；成功后 `markCompleted` 把 CANCELLED 覆盖成 COMPLETED | `BatchTaskService.kt:140-144` |
| 4 | 重试任务永不执行 | `retryTask()` 只入内存队列；Service 已 `stopSelf()`，无人重新拉起 | `TaskQueueManager.kt:121-139`、`BatchTaskService.kt:127,163` |
| 5 | `pause()` 语义错误 | 暂停时 `next()` 返回 null → 循环 break → 弹"完成"通知并停服 | `BatchTaskService.kt:127-164` |
| 6 | Native 压缩永远报成功 | `convertVideo()` 固定 `return true`，无取消机制 | `VideoController.java:810` |
| 7 | Native 压缩原始分辨率 = 流复制 | 宽高不变走 copy 分支，编码参数全部失效 | `VideoController.java:367` |
| 8 | `currentSessionId` 并发覆盖 | 单一 `@Volatile` 字段，多会话并发时取消错乱 | `FFmpegService.kt:21-31,234` |
| 9 | 非压缩批量任务成功后也执行 rotation 逻辑 | 成功路径不看 `taskType` | `BatchTaskService.kt:216-236` |
| 10 | 批量队列零持久化 | 内存队列 + `START_NOT_STICKY` | `BatchTaskService.kt:87` |
| 11 | `build-termux.sh` 检查 android-36 与 compileSdk 37 不一致 | `build-termux.sh` |
| 12 | `markCompleted` 持锁做磁盘 I/O | `TaskQueueManager.kt:62` |
| 13 | `VideoController.copyFile` 死代码且 `transferTo(1,...)` 漏首字节 | `VideoController.java:819`，当前无调用方 |
| 14 | `MetadataService`/`AudioEncoder`/`FFmpegWaveformLoader` 同步旁路 `FFmpegKit.execute()` | 已记入 AGENTS.md |

## 二、第一批修复范围（本次实施）

**只修 #1–#4，改动收敛在三个文件**：

- `TaskQueueManager.kt`
  - `markCancelled()`：同时把任务移出待执行队列
  - 终态保护：`markCompleted()` / `markFailed()` 遇 CANCELLED 不覆盖；`markCancelled()` 遇 COMPLETED 不回退
  - `retryTask()` 返回新建的 `BatchTaskItem?`，供 UI 重启 Service
  - 新增 `clearForTesting()`（仅测试用）
- `BatchTaskService.kt`
  - `cancelTask()` 由 `startForegroundService` 改为 `startService`：取消是控制指令而非新工作，且 Service 若已死则重新 startForegroundService 会因未调 startForeground 崩溃；调用点保证 App 在前台
  - `handleCancelTask()` 末尾对"Service 被取消指令意外拉起且无事可做"的情况 `stopSelf()`
- `BatchTaskActivity.kt`
  - 取消：Service 存活时走 `BatchTaskService.cancelTask()`（会真正 cancel 协程与 FFmpeg 会话），否则回退 `markCancelled()`；异常时回退
  - 重试：`retryTask()` 拿到新任务后调 `BatchTaskService.start()` 重新拉起/复用 Service

## 三、回归隔离分析

- 单任务链路（`TrimFragment` / `MergeFragment` / `ExtractFragment` / `CompressFragment` 单任务模式 / `AudioCompressFragment` / `CustomCommandFragment` 等）直接调用 `FFmpegService.executeCommand/trimVideo/mergeVideos/...`，**本次不修改 `FFmpegService`**
- `TaskQueueManager` 全项目仅 `BatchTaskActivity` + `BatchTaskService` 引用（已 grep 核实）
- `BatchTaskService.start()` 签名不变，`CompressFragment.kt:595` 的批量创建入口不受影响
- #8（`currentSessionId`）涉及 `FFmpegService`，与单任务共用，故**延后单独批处理**

## 四、设计取舍记录

1. **取消用 `startService` 而非 `startForegroundService`**：Android 要求每次 `startForegroundService` 后 5 秒内调 `startForeground`，而 `handleCancelTask` 不满足该契约（潜在 `ForegroundServiceDidNotStartInTimeException`）。取消操作发生在前台 Activity，`startService` 无此要求。
2. **终态保护而非双向回退**：CANCELLED 一旦成立，后续迟到回调（成功/失败）一律丢弃；COMPLETED 不允许被取消回退。剩余极小竞态窗口（取消点击恰逢 FFmpeg 已完成回调落地的毫秒级重叠）会表现为"已取消但文件已生成"，接受该边界。
3. **重试保留原任务记录**：`retryTask` 生成新 UUID 任务追加队列，失败历史保留，与现有 UI 展示一致。
4. **#5（pause）暂不修**：pause/resume 无 UI 入口，属于死代码；修语义属 scope creep，留待接通 UI 时一并处理。

## 五、验证

- 新增 `app/src/test/.../TaskQueueManagerTest.kt`（host JVM）：队列移除、终态保护、重试字段重置、pause 阻塞 next()
- `.\gradlew.bat :app:testDebugUnitTest` + `:app:compileDebugKotlin`
- 真机手动回归项（后续）：批量压缩中途取消（RUNNING/PENDING 各一）、失败后重试、Service 存活时取消

## 第二批修复（同日实施）

| # | 修复 | 方案 |
|---|------|------|
| 8 | `currentSessionId` 并发覆盖 | 改为 `activeSessions` ConcurrentHashMap 追踪全部活跃会话；`cancelCurrentSession()` 取消最近启动的一个（调用方均为单任务 UI，行为不变）；会话完成/协程取消只移除自身，不再互踩 |
| 9 | 非压缩任务跑 rotation | 成功路径 `task.type == COMPRESS` 才执行 tkhd 操作；时间戳回写保留全类型 |
| 11 | termux 脚本误查 android-36 | 改为 android-37 |
| 12 | `markCompleted` 持锁 I/O | 锁内取路径，锁外 `File.length()`，再入锁提交状态 |
| 13 | `VideoController.copyFile` 死代码（含 `transferTo(1,…)` 漏首字节 bug） | 删除方法与未用的 `FileChannel` 导入 |

#8 已知局限（与旧行为一致、非回归）：批量与单任务并发时，单任务取消按钮命中最近启动的会话，可能是批量的。彻底解需按调用方分发会话句柄，涉及全部单任务 Fragment，收益低暂不做。

## 第三批修复（同日实施）

#5 产品决策：不需要暂停/恢复，只保留取消。删除 `TaskQueueManager.pause()/resume()/isPaused`、`BatchTaskService` 的 `ACTION_PAUSE/ACTION_RESUME` 与 `handlePause/handleResume`、通知栏暂停按钮、对应测试用例与 `pause` 字符串资源（zh/en）。

#14 同步旁路治理：新增 `FFmpegService.executeTrackedSync(command, onSessionStarted)`（executeAsync + CountDownLatch，会话运行期注册进 `activeSessions`）；`MetadataService`、`AudioEncoder`、`FFmpegWaveformLoader`（astats + PCM 解码两处）全部改走该入口，不再直接 `FFmpegKit.execute()`。附带修复：`AudioEncoder.cancel()` 原先在阻塞执行结束后才保存 session，永远无法取消运行中会话；现在通过 `onSessionStarted` 在执行期间捕获，取消真正生效。

## 六、遗留问题（后续批次）

| 优先级 | 项 |
|---|---|
| 中 | #10 队列持久化（产品需求确认后决定） |
| 低 | #6/#7 VideoController 返回值与取消、原始分辨率提示（debug 实验页，低价值） |
