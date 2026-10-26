# 存档边界与回归

本次基于干净的 `master@c7ff29d7ad7f21e09375287372d4d382468a38d6`。
四项存档问题均先应用交接补丁、运行真实 Fabric 复现后再处理。
前三项已经修复，实体坏档保存保护已按审阅意见修订；跨 Human/Humanoid 文件的释放交接按用户要求暂停。
没有修改 Human，没有提交或推送。

## 已修复的边界

| 问题 | 根因 | 修改 | 整体回归 |
|---|---|---|---|
| 最新成功回执遗漏 | Store 从 HashMap 提供回执，截取尾部不代表最新提交 | 按 `committed.revision` 升序排列后保留最高的 32 条 | `audit02NewestReceiptSurvivesEntityNbtRoundTrip`：提交 100 笔、核对完整 69–100 窗口、实体 NBT 往返并重放最后一笔 |
| 错误类型列表被吞空 | `getList(key, BYTE_ARRAY_TYPE)` 先返回空列表，之后检查已经失去原始类型 | 世界队列与实体回执直接检查原始 `NbtList`；实体全部字段校验后才更新缓存，失败时隔离身体功能并原样保存标签 | `audit03WorldQueueRejectsWrongElementType`、`audit03EntityReceiptsRejectWrongElementType`；另保留合法空列表往返 |
| 坏账本使身体退回原版 HP | `isReady` 失败被当成不归本宿主管理 | 先检查活动世界、线程及实体认领，再将已认领实体的装配失败映射为 `INTERNAL_ERROR` | `audit04CorruptLedgerMustNotBecomeUnbound`：真实坏档启动、恢复已绑定实体、拒绝伤害且原版 HP 不变；未认领牛仍为 `unbound`，随后保存不覆盖坏文件 |

这里继续保留原有类名及 `audit02`–`audit05` 方法名，便于和交接材料对应。
测试放在正常 `fabric/src/gametest` 源集，默认 GameTest 包含原有整体回归和新增存档回归。
坏档与强退测试必须选择独立模式；强退测试的恢复断言保持失败，不能作为已经修复的证明。

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

## 已暂停的释放交接

Human 当前源码基线是 `88cba0a6ca81bdafdd9bfe05450f9e4248f82c37`。
已核对 `HumanWorldState.release`、`renewEnvironment` 和其 Wiki：

1. 同一 `release_id` 和完整相同请求返回 `duplicate=true`；同 ID 改变位置或库存返回 `REQUEST_ID_CONFLICT`。
2. `released` 与 `duplicate=true` 只说明请求已在内存登记，随后 `markDirty`；都不表示文件已经落盘。
3. `release_ids` 自首次受理起保留 `8,400,000` 世界模拟毫秒，即七个游戏日；待入账记录未处理完时不会清除。
4. 入账完成且超过期限后，原 ID 会被移除，再次发送原请求会被当成新释放。

原实现的故障窗口是：先保存接收端旧状态与发送队列，接收端内存受理后删除发送队列，
只保存发送端，随后进程强退。重启时两边都没有该请求。

仅让 Humanoid 保留记录至重启再发送，可以覆盖短期窗口，但有两个无法由当前公开接口排除的情况：

- 本进程首次重发已被接收，但随后响应丢失或本地处理失败；下一次收到的 `duplicate` 仍可能只来自本进程内存。
- 长期运行或关闭自动保存后，发送端旧记录仍在磁盘，而接收端已经入账并清理去重 ID；重启重发会重复入账。

因此没有将有限期限去重当成永久持久化确认，也没有读取或反射 Human 私有运行状态来实现生产接线。
测试读取真实 Human 状态只用于故障证据。按任务限定，暂停第四项实现，保留失败回归。

所需 Human 最小协作改动：通过现有公开 Protobuf 服务提供可验证的持久化确认，
仅在释放 ID、原请求和待入账事实成功持久化后确认；磁盘失败必须返回失败。
Humanoid 只有收到此确认后才删除原请求。若保留普通 `released` 的内存受理语义，
应新增明确的持久化结果或查询操作，不能重新解释 `duplicate`。

确认丢失、发送端删除尚未落盘就再次崩溃时，仍要允许原请求安全重发。
Human 的去重记录必须覆盖这段未决交接期，例如在发送端确认本地删除前保留去重事实；
不能对尚未完成交接的 ID 直接沿用七日清理。
Human 契约、持久化错误处理及去重回收机制需要一起调整，本次均未修改。

该方案还需覆盖：首次受理后未落盘、目标先落盘、重复响应丢失、发送端删除未落盘、
任一文件写入失败、超过七日的未决请求、相同 ID 内容冲突，以及反复强退重启。
这些是后续协议修复的验收条件，本次没有声称通过。

## 实际执行

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

本轮按要求只运行 `full` 和 `bad-ledger`，未重新运行释放强退模式。
第四项仍未修复，其失败回归和故障证据保留，等待用户决定协议方案。

日志保存在 `fabric/build/persistence-logs/`，证据保存在 `fabric/build/persistence-evidence/`。
本轮命令和退出码另存为 `fabric/build/persistence-logs/save-protection-final-results.json`。
XML 报告位于 `fabric/build/repro-<模式>.xml`；默认整体运行使用 `repro-full.xml`。
`release-before` 和 `release-after` 共用 `fabric/build/repro-release/world`，必须按顺序执行，
前者预期非零，不能用 `&&` 跳过后者。

## 自查与未覆盖

修改保持在现有 Store、NBT、健康宿主和 GameTest 接线中，没有增加库或第二份身体账本。
按版本排序在保存边界完成，原始列表类型校验在读取边界完成，错误归属在健康宿主完成。

代码形状自查：第 4 条，`HumanoidHealthHost.exchange` 缓存服务器引用；
第 9 条，`LivingEntityHumanoidMixin.meridian$beginRead` 提取完整回执读取，
并将身体字段校验提取到 `meridian$readBody`；坏档保存回归提取断言与编码方法，
`ReleaseCrashBeforeGameTest` 提取标记写入，保持方法不超过约 40 行；
第 8、10 条，测试校验说明异常含义，清理只适用于副本或旧缺陷背景的注释。

未检查随机物理断电、磁盘控制器缓存或硬件故障、多人长时运行、全部附属各自的完整测试集。
第四项仍有真实可复现的真元丢失风险，按用户要求暂不修复，也未修改 Human。
