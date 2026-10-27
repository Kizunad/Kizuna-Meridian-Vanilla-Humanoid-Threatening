# 存档边界与回归

前三项存档问题与实体坏档保存保护已在前轮修复。
当前基线为 Humanoid `master@fd5d382`、Human `main@88cba0a`，继续在两端已有未提交修改上修正释放交接。
四项问题均有真实 Fabric 复现或回归；本轮同时修改 Human 与 Humanoid，没有提交或推送。

## 已修复的边界

旧账本（只有 `next_sequence` 和 `pending`）加载时补空的 `pending_forget`，再执行全部新格式校验。
迁移不改变输入标签、序号或完整释放请求，并标记待保存，下一次世界保存写入新格式。
字段已存在但顶层或元素类型错误、旧字段缺失、负序号和重复请求仍拒绝加载。
`HumanoidWorldStateTest.legacyWriterFilesMigrateAndPersistWithoutChangingRequests` 使用旧写入器布局
分别写入空队列和非空队列文件，经新读取器加载、保存并再次重载。

| 问题 | 根因 | 修改 | 整体回归 |
|---|---|---|---|
| 最新成功回执遗漏 | Store 从 HashMap 提供回执，截取尾部不代表最新提交 | 按 `committed.revision` 升序排列后保留最高的 32 条 | `audit02NewestReceiptSurvivesEntityNbtRoundTrip`：提交 100 笔、核对完整 69–100 窗口、实体 NBT 往返并重放最后一笔 |
| 错误类型列表被吞空 | `getList(key, BYTE_ARRAY_TYPE)` 先返回空列表，之后检查已经失去原始类型 | 世界队列与实体回执直接检查原始 `NbtList`；实体全部字段校验后才更新缓存，失败时隔离身体功能并原样保存标签 | `audit03WorldQueueRejectsWrongElementType`、`audit03EntityReceiptsRejectWrongElementType`；另保留合法空列表往返 |
| 坏账本使身体退回原版 HP | `isReady` 失败被当成不归本宿主管理 | 先检查活动世界、线程及实体认领，再将已认领实体的装配失败映射为 `INTERNAL_ERROR` | `audit04CorruptLedgerMustNotBecomeUnbound`：真实坏档启动、恢复已绑定实体、拒绝伤害且原版 HP 不变；未认领牛仍为 `unbound`，随后保存不覆盖坏文件 |
| 释放交接强退丢真元 | 把内存受理或 `duplicate` 当作写盘确认，发送端先删除且保存 | Human tick 末合并保存后确认；Humanoid 持久删除后发送遗忘，完成历史被回收 | 两端释放持久化 GameTest，以及 `release-before` → `release-after` 真实强退重启 |

这里继续保留原有类名及 `audit02`–`audit05` 方法名，便于和交接材料对应。
测试放在正常 `fabric/src/gametest` 源集，默认 GameTest 包含原有整体回归和新增存档回归。
坏档与强退测试必须选择独立模式；`release-before` 预期以 87 强退，随后同世界的 `release-after` 必须成功恢复。

实体读取 `meridian_humanoid` 前先保存完整 `NbtCompound.copy()`。
字段校验失败时清除运行中的身体引用、标记加载失败，并保留原始复合标签；
异常仅在身体标签边界处理，实体其余 NBT 仍正常加载。
后续实体、区块和服务器保存都写回原始标签的独立副本，不能因身体坏档而抛保存异常。
健康请求持续返回 `INTERNAL_ERROR`，不会使用旧身体或新建身体。
每个实体对象生命周期内只记录一次加载错误，多次查询、保存或重复读取坏标签都不重复记录。

坏回执回归保留错误列表类型、身体字节及未知字段，核对完整标签内容和编码字节，
并验证修改读取输入或保存输出不会污染保留副本；随后执行真实服务器保存并再次核对。
NBT 复合标签没有键顺序约定，`copy()` 可能改变 HashMap 遍历顺序；
字节比较前统一映射布局，字段类型和载荷仍逐字节一致，不要求整个实体文件的编码顺序或压缩字节一致。

## 释放交接协议与写盘边界

Human 和 Humanoid 通过必装的 `meridian_environment_host.exchange(byte[])` 交换 Protobuf；生产代码不读取 Human 私有状态。双方一起更新，不保留旧接口降级路径。

| 协议 | 字段与语义 |
|---|---|
| `ReleaseResult.persisted = 3` | 释放 ID、完整原请求及待入账事实或余额已包含在一次成功的持久保存中 |
| `duplicate = 2` | 仅表示此前受理，原语义不变；不授权删除 |
| `EnvironmentHostRequest.forget_release = 13` | 新增 oneof 操作，内容为已持久删除原请求的释放 ID；接原最大号 12 |
| `EnvironmentHostResponse.forgotten = 15` | 新增结果，接原最大号 14；`ForgetReleaseResult.persisted = 1` 为 true 才确认删除已持久化 |

同 ID 同完整内容不重复入账，位置或库存冲突返回 `REQUEST_ID_CONFLICT`；ID 不得复用。七个游戏日不再触发自动回收。

### Minecraft 保存时机与平台差异

已核对 Minecraft 1.20.1 Yarn 映射 JAR 的实际字节码：`markDirty()` 只设内存标记，`PersistentStateManager.save()` 才调用 `PersistentState.save(File)`。世界保存经过 `ServerWorld.saveLevel()`；自动保存每 6000 个 tick（20 TPS 时五分钟），正常关服也保存。`savingDisabled` 跳过普通世界保存。原版直接覆盖，捕获异常后仍清 dirty，没有刷盘确认。

两端保存均保留原版 `data`、`DataVersion` 封装，写同目录 `.dat.tmp`、关闭压缩流、对文件执行 `FileChannel.force(true)`、使用 `ATOMIC_MOVE + REPLACE_EXISTING` 原子替换正式文件。**文件 force 和原子替换始终必须成功。** Unix 平台还同步目录及其上级目录；Windows 不打开不支持的目录通道，跳过目录同步，采用文件 force 和 NTFS 原子移动的元数据日志保证。支持目录同步的平台上的真实 I/O 错误仍算保存失败，不被吞掉。

“写盘确认”指适用于该平台的全部步骤收到操作系统成功返回。替换前失败保留旧文件；替换后目录同步失败可能已更新文件，仍不确认。失败保留 dirty 和待确认事实，日志报告异常，下一 tick 继续尝试；仅从正式文件恢复，临时文件下次覆盖。操作系统及文件系统须兑现其同步和原子替换语义。

### tick 末合并与最坏延迟

Human 首次受理只登记内存并返回 `persisted=false`，END_SERVER_TICK 合并保存所有未确认释放和待保存遗忘。成功后更新持久 ID 集合；重复请求只查询该集合，不写盘。普通世界保存与合并保存共用 `lastSaveTick`，**运行中一个 tick 最多尝试一次 Human 持久保存，失败也算一次**。正常关服在停止 tick 后提交最终脏状态。

Humanoid 每 tick 重试原请求，收到 true 后才从待释放转成待遗忘，并合并保存自己的文件。正常磁盘下，从 Human 内存受理到 Humanoid 收到释放确认最多两个服务器 tick，20 TPS 下约 100 毫秒；边界包含两个模组回调顺序和当 tick 已执行世界保存的情况。单次文件保存仍在主线程，耗时会拉长 tick。磁盘故障、暂停或低 TPS 没有有限墙钟延迟保证。

### 持久删除后遗忘

1. Human 持久保存 ID、完整原请求、待入账事实或余额，重试返回 `released.persisted=true`。
2. Humanoid 删除原请求并登记 `pending_forget`，**先将这两个变化保存到同一份自己的文件**。失败时仍不能发送遗忘，旧正式文件可恢复原释放。
3. 只有成功保存中的待遗忘 ID 才能发 `forget_release`。Human 尚未入账时返回 false，保留去重原请求；主世界等待对应 30 秒窗口，非主世界等待下一个 30 秒边界。
4. 入账后 Human 内存删除去重项，tick 末保存余额与删除；此前响应为 false。保存成功或 ID 已不存在时返回 `forgotten.persisted=true`。
5. Humanoid 收到遗忘确认后删除待遗忘 ID 并保存。失败或响应丢失后只会重试遗忘，不会重发原释放。双方不保留永久遗忘墓碑。

正常情况下 Human 去重项及 Humanoid 待遗忘 ID 的驻留时间最多为剩余 30 秒入账窗加少量交接 tick；保守按 30 秒加 6 tick（20 TPS 下约 30.3 秒）估算完整往返。故障会延后，未完成事实不能按时间丢弃。

### 崩溃窗口与存档上界

| 崩溃位置 | 重启行为与安全依据 |
|---|---|
| 受理前、受理后未落盘 | 发送端原请求仍在；同 ID 重发，未落盘事实可以重新受理 |
| Human 已落盘，首次或重复确认丢失 | 发送端继续重发；Human 用已保存完整请求去重，不再次入账 |
| Humanoid 内存删除，自己的保存未完成或失败 | 正式旧文件仍含原请求；Human 尚未获遗忘证明，继续去重 |
| Humanoid 删除及待遗忘已落盘，遗忘未送达 | 只恢复待遗忘 ID；Human 仍拥有待入账事实或余额 |
| Human 已入账，删除去重项尚未落盘 | 正式文件仍含去重项及待入账事实或余额；待入账与余额只从同一快照恢复，遗忘继续重试 |
| Human 删除已落盘，遗忘响应丢失 | 发送端仍保留待遗忘 ID；Human 对不存在的 ID 幂等确认 |
| Humanoid 删除待遗忘 ID 尚未落盘 | 只会多发一次遗忘，不会恢复原释放；确认后重新保存 |

“有界”指不再保存已经完成的累计死亡历史：Human 去重空间为 O(未确认释放 + 等待入账/遗忘的释放)，Humanoid 为 O(待释放 + 待遗忘)，释放序号仍占固定大小。稳定速率 λ 笔/秒、磁盘正常时，活动历史约受 λ ×（30 秒 + 交接延迟）限制；准确条数取决于瞬时输入峰值。无限故障或无限新输入下没有固定字节上限，安全性要求保留全部未决积压。整份 Human 文件还含玩家和区域，这些资源的规模不在本次释放历史回收范围。

遗忘是发送端不可撤回的“原请求已持久删除”声明；完成遗忘后不得人工回滚单侧旧备份并重放 release。

### 验收与测试对应

| 验收条件 | 测试 |
|---|---|
| 首次受理未落盘、duplicate 不等于确认 | Human `memoryAcceptanceAndDuplicateDoNotConfirmDisk`；Humanoid `acceptanceAndDuplicateWithoutPersistenceKeepSenderRecord` |
| 目标先落盘、反复重启只入账一次 | Human `durableReleaseSurvivesRepeatedReloadAndCreditsOnce` 四轮真实文件重载；两轮 `release-before` → `release-after` JVM 强退恢复 |
| 首次及重复响应丢失 | Humanoid `lostInitialAndDuplicateResponsesAndUnsavedDeletionRetrySafely` |
| 发送端删除未落盘、自己的文件写入失败 | `senderDeletionWriteFailureKeepsReplayableOldFile`；统计公开请求，确认本地持久删除前遗忘调用次数为 0 |
| Human 写入/替换失败 | Human `failedWriteKeepsOldFileAndDuplicateMustRetryDisk`、`failedAtomicReplacementDoesNotConfirmDisk`；Humanoid `receiverWriteFailureDoesNotConfirmAndKeepsSenderRecord` |
| 超过七日未决、同 ID 内容冲突 | Human `unresolvedReleaseSurvivesSevenDaysAndRejectsConflictingContent` |
| Windows 目录同步不支持 | 两端单元 `DurableStateFileTest.unsupportedDirectorySyncStillSavesAndReplacesFile`、`unsupportedDirectorySyncDoesNotSkipFileForce`；Humanoid 单元 `unsupportedDirectorySyncSavesActualWorldState`；两端 GameTest `unsupportedDirectorySyncStillConfirmsPersistence`、`unsupportedDirectorySyncPersistsSenderDeletionBeforeForget` |
| 同 tick 合并保存、失败也仅一次 | Human `batchAndWorldSaveShareOneAttemptPerTick`（32 笔）、`failedBatchOnlyRetriesNextTick` |
| 遗忘必须等待入账、删除写失败后重启恢复 | Human `forgetWaitsForCreditAndDurableDeletionThenReclaimsHistory`，同时检查余额守恒及去重项已回收 |
| 遗忘请求未送达、响应丢失、两端回收 | Humanoid `durableForgetRetriesLostResponsesAndReclaimsBothFiles`；真实窗口后读取两端正式文件，旧发送文件重载只含待遗忘 ID |
| 待遗忘存档损坏 | Humanoid 单元 `forgetListRejectsWrongTypeBlankAndDuplicateIds` |
| 正常生命周期 | 原 `killedAndDiscardedReleasesRetryUntilPersisted` 等 21 项整体回归继续运行 |

## 前轮复现与验证记录

所有命令的工作目录都是本仓库，使用 Java 17 和完整 Fabric 附属装配。
复现补丁先执行 `git apply --check --whitespace=error-all`，再实际应用。
补丁的证据目录从仓库外改为 `fabric/build/persistence-evidence`，避免写到其他项目目录。

独立测试世界复用本地已有的 EULA 同意文件：

```bash
mkdir -p "fabric/build/repro-normal" "fabric/build/repro-bad-ledger" "fabric/build/repro-release"
cp "fabric/build/gametest/eula.txt" "fabric/build/repro-normal/eula.txt"
cp "fabric/build/gametest/eula.txt" "fabric/build/repro-bad-ledger/eula.txt"
cp "fabric/build/gametest/eula.txt" "fabric/build/repro-release/eula.txt"
```

修复前运行：

```bash
"./gradlew" --offline --console=plain ":fabric:runGametest" -PreproMode=normal
"./gradlew" --offline --console=plain ":fabric:runGametest" -PreproMode=bad-ledger
"./gradlew" --offline --console=plain ":fabric:runGametest" -PreproMode=release-before
"./gradlew" --offline --console=plain ":fabric:runGametest" -PreproMode=release-after
```

| 模式 | 修复前结果 |
|---|---|
| `normal` | 5 项中 3 项失败：最新回执遗漏、世界队列错误类型、实体回执错误类型；时钟与坏档夹具准备通过 |
| `bad-ledger` | 1 项失败，返回 `UNBOUND`，原版 HP 从 20 降至 19 |
| `release-before` | 达到指定窗口后 GameTest JVM 以 87 强退；Gradle 返回 1，符合故障注入设计 |
| `release-after` | 同一世界恢复成功，发送端与接收端都没有原释放 ID，1 项失败 |

最初一次正常模式启动因新目录缺少 EULA 文件而失败；复用已有文件后才取得上述业务复现结果。

本轮保存保护修订在 2026-10-27 完成。新回归先在旧保护实现上运行失败：
实体读档抛出 `Loading entity NBT`，停服保存继续抛出 `Saving entity NBT`，
见 `save-protection-before-full.log`。改为保留标签后执行：

```bash
"./gradlew" --offline --console=plain test build :server:test --rerun
"./gradlew" --offline --console=plain ":fabric:runGametest" -PreproMode=full
"./gradlew" --offline --console=plain ":fabric:runGametest" -PreproMode=bad-ledger
```

| 检查 | 最终结果 | 日志文件名 |
|---|---|---|
| `test build :server:test --rerun` | 退出 0；构建通过，5 项 JUnit 实际重跑通过，没有失败或跳过 | `save-protection-final-build.log` |
| `full` 整体 GameTest | 退出 0；原有 15 项与新增 6 项，共 21 项通过；坏身体标签经过服务器保存仍完整，加载错误只记录一次 | `save-protection-final-full.log` |
| `bad-ledger` | 退出 0；1 项通过，健康接口 `FAILURE/INTERNAL_ERROR`；Damage 显式抛错，原版 HP 保持 20，坏文件未被覆盖 | `save-protection-final-bad-ledger.log` |
| `python3 "scripts/check_docs.py"` | 文档引用检查通过 | 终端检查 |
| `git diff --check` 与新增文件空白检查 | 通过 | 终端检查 |

坏档测试首次修复后运行被 Damage 按预期抛出的 `INTERNAL_ERROR` 中断；
正式回归现已接受并校验这个具体失败，仍要求 HP 不变、公开失败码正确且坏文件未覆盖。
没有吞掉其他运行错误或放宽成功断言。

上述保存保护记录属于前轮，当时第四项尚未修复；本轮结果见下节。

日志保存在 `fabric/build/persistence-logs/`，证据保存在 `fabric/build/persistence-evidence/`。
本轮命令和退出码另存为 `fabric/build/persistence-logs/save-protection-final-results.json`。
XML 报告位于 `fabric/build/repro-<模式>.xml`；默认整体运行使用 `repro-full.xml`。
`release-before` 和 `release-after` 共用 `fabric/build/repro-release/world`，必须按顺序执行，
前者预期非零，不能用 `&&` 跳过后者。

## 本轮执行与未覆盖

2026-10-27，两个原工作区，Gradle 编译、测试与 GameTest 使用 Java 17，保持完整附属装配。Human 新协议和 JAR 先构建，Humanoid 随后读取新产物。旧生成测试世界保留为 `world.before-review`，新模式从独立测试世界起跑；每组 before/after 共用同一个世界，第二轮继续用它。

```bash
# Human 仓库
"./gradlew" --offline --console=plain test build demo :fabric:compileGametestJava
"./gradlew" --offline --console=plain :fabric:runGametest

# Humanoid 仓库
"./gradlew" --offline --console=plain test build :server:test --rerun
"./gradlew" --offline --console=plain :fabric:runGametest -PreproMode=full
"./gradlew" --offline --console=plain :fabric:runGametest -PreproMode=bad-ledger
"./gradlew" --offline --console=plain :fabric:runGametest -PreproMode=release-before
"./gradlew" --offline --console=plain :fabric:runGametest -PreproMode=release-after
# before、after 顺序再执行一轮；before 预期非零，不能用 && 跳过 after。

# 两个仓库分别执行
python3 "scripts/check_docs.py"
git diff --check
```

| 检查 | 最终结果 | 日志 |
|---|---|---|
| Human test/build/demo、GameTest 编译 | 退出 0；42 项 JUnit，无失败/跳过，演示通过 | Human `review-final-build.log` |
| Human GameTest | 退出 0；原有 7 项、新增 9 项，共 16 项通过 | Human `review-final-human-gametest.log` |
| Humanoid test/build | 退出 0；9 项 JUnit，无失败/跳过 | `review-final-build.log` |
| Humanoid full | 退出 0；原有 21 项、新增 6 项，共 27 项通过 | `review-final-full.log` |
| Humanoid bad-ledger | 退出 0；1 项通过，坏档未覆盖 | `review-final-bad-ledger.log` |
| 第一轮 before → after | before JVM 87、Gradle 1；after 退出 0，恢复及遗忘回收通过 | `review-final-before-1.log`、`review-final-after-1.log` |
| 第二轮 before → after | before JVM 87、Gradle 1；after 退出 0，同世界再次恢复及回收通过 | `review-final-before-2.log`、`review-final-after-2.log` |
| 两端文档与 diff | 退出 0 | `review-final-docs.log`、`review-final-diff.log` |

日志及结果记录位于各仓 `fabric/build/persistence-logs/`，命令及退出码存于 `review-final-results.json`；本轮 GameTest XML 位于 Human `fabric/build/gametest/build/gametest-results.xml` 与 Humanoid `fabric/build/repro-<模式>.xml`。Human 上一级旧报告不是本轮结果。before 的成功证据为强退前断言、`senderForgetSaved=true`、JVM 87 和 after 恢复，不会生成通过 XML。after 继续正常 tick，最终要求 `REPRO-A-05-RECLAIM` 显示两端历史均回收。

首轮新单元夹具曾因未初始化 Minecraft 版本而失败，定位为 `NbtHelper.putDataVersion` 的 `Game version not set`；补充 `SharedConstants.createGameVersion()` 后重跑通过。故障注入产生预期 I/O 日志；原 mock 玩家缺少 UI 频道及坏身体夹具的错误日志不代表真实客户端已受测试。

增强遗忘丢包后，复用 GameTest 世界曾因旧区域窗口晚于测试服务器重启后的时钟而未能在 600 tick 内回收，诊断见 `review-debug-full-2.log`。遗忘丢包夹具改用无区域账户的明确时间边界，强退夹具每轮选择新区域；仍使用生产协议、实际文件、正常 tick 和同一重启世界。生产强退也可能回滚尚未保存的世界时钟，延后旧窗口入账；期间继续保留未决记录，不能提前回收。

代码形状自查：第 1、2、9 条，Human 释放回归拆开 builder、提前构造内层消息，保存读取按身体、区域、交接事实拆分；第 3、4、5 条，Human 配置改为 if/else，释放复用已读取的 ID、位置，读取余额不再复用加工变量；第 7、8 条，命名区块宽度、批量、窗口和 tick 常量，时间/库存校验用途写清；第 9 条，异步回归按故障窗口拆出断言并修正缩进和长行。

未检查 Windows 实机、随机物理断电、控制器缓存/硬件故障、网络文件系统、多人持续死亡时的长时间性能、从实体身体到首次登记发送队列的跨文件事务，以及全部附属各自完整测试集。单 tick 仍有一次整份同步保存；故障期间未决积压必须增长。未提交、未推送。
