# 生人威胁批次实现计划

本仓库只收活着的类人生物（生人）。支持村民、流浪商人、掠夺者、卫道士、唤魔者、女巫和幻术师；类人亡灵不在范围内。每种生人生成游炁与真炁两档变体，身体都使用 Human Addon 的 `human:adult` 完整结构和 36 条经脉。

当前实现状态：阶段 1–3 的本仓库部分已经落地。阶段 4 取消：真元属性没有被动战斗效果，只在被使用时（例如功法）才有区别。

阶段 1 的纯 Java 生成器已经落地：

- 游炁层使用左右肺、大肠、胃、脾共 8 条经脉；真炁层使用左右心、小肠、肾、膀胱共 8 条经脉；其余 20 条保持未开拓。
- 游炁变体随机开启 1–8 条游炁层经脉，真炁变体完整开启游炁层并随机开启 0–8 条真炁层经脉。每条已开经脉的内腔容量增加 1625。
- 内腔和外壁按配置范围装填，未开拓经脉保留少量自然真元；所有库存先按实际密度换算体积，再通过 Framework 快照校验。
- 区域库存按属性合并相态，过滤 `meridian:none` 和目录外属性后调用 Qi-Variants 注册表。相同世界种子与实体 UUID 使用同一随机种子，结果可复现。
- 物种参数只改变开拓数、装填比例、外壁比例和手经/足经抽样权重；不会改变 Human 模板或属性目录。

配置资源为 `server/src/main/resources/meridian-humanoid.toml`。Fabric 阶段已完成以下接线：

- `HumanoidHealthHost` 只认领七种实体；首次健康查询从 Human `meridian_environment_host` 取得区域库存和世界时钟，生成失败时不创建临时身体。
- 健康宿主接受 `exertion`，核对命令身体 ID 与实体实际绑定，使用环境宿主提供的可信时钟，保留请求 ID 和预期 revision，经 Framework 普通命令入口原子做功。成功只返回身体，不附带健康效果回执；能力不足返回原始失败码，不记宿主错误。`HumanoidGameTest.exertionChargesOnceRejectsCapacityAndChecksBoundIdentity` 覆盖扣账、重放不重复、容量拒绝不改身体及跨身体拒绝。
- 身体快照、按提交 revision 选择的最新 32 条回执、区域来源和当前实体所有者写入实体 `meridian_humanoid` NBT。Framework 时间推进和健康回执中的 `released_qi` 会进入本模组世界释放账本（宿主自写 `data/meridian_humanoid.dat`，跟随世界保存）；所有者字段阻止其他实体借用快照，并兼容已有存档的身体 ID。
- 世界释放队列与实体回执从原始 NBT 列表检查元素类型，错误类型拒绝加载。实体身体标签读取失败时保留完整副本，保存时原样写回并只记录一次加载错误；世界账本或实体身体标签恢复失败时，已认领实体的健康请求返回 `INTERNAL_ERROR`，不退回原版 HP。
- 释放账本保存完整 `RegionRelease`，每 tick 重试；Human tick 末合并保存后才返回 `released.persisted=true`。Humanoid 持久删除原请求并保存待遗忘 ID 后请求回收，Human 入账并持久删除去重项后确认；完成历史不再永久保留。两端文件同步与原子替换始终必须成功，Windows 跳过不支持的目录同步。协议与回归见[存档边界与回归](Persistence.md)。`UNLOADED_*` 与 `CHANGED_DIMENSION` 保留身体，`KILLED`/`DISCARDED` 结算并释放。
- 已绑定身体的村民（游炁和真炁）雷击时不转女巫，沿用原版和平难度分支的普通雷击处理：仍是同一个村民、保留原身体、照常着火，伤害经过 Damage-Addon 与健康宿主；无身体村民保留原版转化。注入只改变村民雷击入口的难度判断，不修改世界难度。
- 村民转僵尸村民后不再属于生人，原身体按死亡路径释放。类人身体快照登记为 Human `/meridian body status` 的非玩家视图，输出档位、开拓经脉列表、主属性、密度、装填体积和区域来源。

本仓库的 Fabric 工程通过 Framework 的 `full-assembly.gradle` 使用完整装配，装入范围由 Framework 统一装配清单决定，缺少产物明确报错。Framework、Qi-Variants、功法库、Damage、PA Mobs-Addon 与 Human 的 server/shared 在 settings 中以源码组合，编译和开发运行使用其当前输出；Human Fabric 与破门斩、基础功法等高层附属只在运行时按清单读取已构建的安装 JAR，组合构建不反向依赖本仓库。真元属性不带被动战斗效果，只在被使用时才有区别。

## 分档刷怪蛋与调试命令

独立创造栏页「类人」提供七种生人各两档，共 14 个刷怪蛋；功法卷轴仍在「功法」页。
中文名称为「卫道士刷怪蛋（真炁）」等。两档都用该物种的原版蛋图标，真炁另加附魔光效区分
（26.2 的原版蛋改为逐物种贴图，不能再按配色提亮）；幻术师没有原版蛋，沿用唤魔者的蛋。
图标由 `assets/meridian_humanoid/items/` 的物品模型定义，没有客户端代码。

物品 ID 为 `meridian_humanoid:<species>_<variant>_spawn_egg`。
`species` 可选 `villager`、`wandering_trader`、`pillager`、`vindicator`、`evoker`、`witch`、`illusioner`，
`variant` 可选 `wandering_qi`（游炁）或 `true_qi`（真炁）。
原版 `/give` 从物品注册表补全这些 ID；持蛋右键方块生成，生存消耗一个，创造不消耗。
分档蛋用于方块放置，不提供改刷怪笼、繁殖或发射器行为。

```text
/give @s meridian_humanoid:vindicator_true_qi_spawn_egg
/meridian humanoid spawn vindicator true_qi ~ ~ ~5
/meridian humanoid spawn vindicator wandering_qi
/meridian body status @e[type=minecraft:vindicator,sort=nearest,limit=1]
```

`spawn` 登记在 Framework 的 `/meridian` 注册点，需要权限 2，参数与补全见 [Framework 指令页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki/Commands)。
物种为实体 ID，补全七种生人与全部登记物种；档位可选 `mortal`、`wandering_qi`、`true_qi`，
省略时登记物种用物种表的 `default_tier`，内置生人按自然出生抽档；省略位置时生成在命令源位置，
成功反馈实体名称、档位和 UUID。登记物种可以提供放置钩子，见 [物种注册](Species-Registration.md)。
蛋和命令共用 `HumanoidSpawns`，先走原版装备初始化，再在首次身体创建时指定档位；
已有身体不得覆盖。其余密度、库存、开脉和功法分配沿用原有规则，指定出生立即完成自动学卷。
自然生成仍按世界种子和 UUID 随机抽档；强制出生也保留原抽档随机数消耗，后续随机采样顺序不变。

`HumanoidSpawnGameTest` 覆盖十四蛋与独立创造页、两档卫道士的蛋/命令出生及 body status、
真炁自动学卷、默认与相对坐标、自然随机及采样顺序、省略档位的自然抽档、凡人档不开脉、
内置物种拒绝放置钩子、补全、非法参数和权限拒绝。

## 调试入口

调试用途，不进正式玩法。`HumanoidHealthHost.debugQi(LivingEntity, DebugQi)` 是调试附属改写类人真元库存的唯一公开入口，
权限检查留在调用方命令层。入口只在服务端主线程进入当前宿主的同一身体事务，以 `meridian_debug:command`
身份提交 `DebugQi`，由 Framework 校验调试授权、版本、时钟和容量；返回 Framework 失败码，成功为 `FAILURE_CODE_UNSPECIFIED`。
非主线程为 `ACCESS_DENIED`，非类人实体或正文未读完为 `BODY_NOT_FOUND`，宿主未就绪或读档失败为 `INTERNAL_ERROR`。
类人快照、疲劳与复活仍走公开的 `meridian_health_host` 协议，功法状态走功法宿主入口，不另开字段访问。
`HumanoidDebugQiGameTest` 用八个新生成的真炁卫道士验证连补两次与全身覆盖都成功、每条内腔不越界，非类人实体返回 `BODY_NOT_FOUND`。

## 功法宿主

`HumanoidTechniqueHost` 通过 `meridian_technique_host` 认领同一实体身体，检查两手持物。知识、活动、冷却和曲线保存在 Framework 的身体进度中；宿主仅保存当前加载实体的世界效果回执和移动限速。卸载与死亡清理活动、姿势和移动限速；读取起手中的旧 NBT 时只恢复清理，不补发离线打击。

七种生人都按学卷表学卷。表在 `meridian-humanoid-brain.toml` 的 `[volumes]`，每个物种按境界档写明卷与概率，概率按 UUID 与卷 ID 确定性分桶。默认表：

- 七种生人两档都学基础功法的调息、轻步、蓄力三卷。
- 卫道士游炁另学破门上卷；真炁另学上卷、中卷，下卷概率 0.1。

学卷发生在宿主首次为实体准备会话时：显式功法请求、分档蛋/命令出生，或大脑第一次遇到威胁。已学的卷再学不变，不额外改变身体生成器随机序列。启动后第一个服务器 tick 检查表里每一卷都已安装，缺卷直接报错。

移动修正使用稳定施放 UUID 与 `MULTIPLY_TOTAL=(倍率-1)`，冲量独立执行。
`isCasting` 只表示限速施放，管原版攻击互斥与举武器姿态，不再表示禁止移动。收势、承接、打断、死亡与卸载移除修正。
大脑判断自己是否在施放看有无当前施放（`hasActiveCast`），不限速的式（调息、轻步、蓄力）也算。
受击打断不在类人宿主判定：原版伤害附属命中后写入功法库的僵直，与玩家同一入口；宿主受伤回调只在死亡时恢复会话。

表现通过硬依赖 Mobs-Addon 的 `MobAnimationServer.playFrom/stop` 接入 PA，与玩家共用 Human 公开的
`HumanTechniqueAnimations` 映射并保留原始起播时刻。叩门、破门、撞门使用专属主副手片段，
命中分别对齐 34、64、48 tick；其余五式暂用 `attack_main/off`。左右惯用手由 PA 运行时镜像。
原版举斧不再是渲染要求；自动移动、举斧预备和挥砍均由 Mobs-Addon
的客户端自动层替换，显式功法动作优先，装备继续渲染。

世界打击和劈门使用功法库共享执行器的生物策略：排除友队与袭击生物，遵守 `mobGriefing`，不扣物品耐久。剂量与单次挥手做功交由 Damage，不为每个横扫目标重复做功。上、中卷费用可使用达到最低相态的任意属性库存，不能要求真炁卫道士额外持有固定的无属性游离态库存。
功法活动保留 `meridian_technique:caster` 调用身份，Framework 可据此核验万门一斩能力曲线的来源。
腿部功率由四块主要腿肌分担，不能将目录的整体功率重复施加到每块肌肉。

## 类人大脑

`HumanoidBrain` 接管物种表里 `brain = true` 的生人（七种原版默认都接管，见[物种登记](Species-Registration.md)）。`brain = false` 的物种完全不接管：不决策、不占原版控制、不改恐慌与逃跑。AI 关闭或已死亡的实体也不接管。

每个实体按 ID 错相，每 `decision_interval_ticks`（默认 10）决策一次；跃步飞行中不改主意。决策顺序：

1. **找威胁**：上一个对手在遗忘距离内或看得见时继续追着它；否则从原版目标、攻击者、正以我为目标的生物、受击记忆和村民的近处敌对记忆里取最近的一个。
2. **无威胁**：交还原版控制，不准备身体、不记决策。已有会话且会恢复式的实体例外：疲劳达门槛就调息。
3. **有威胁**：读身体和对手的施放预报，评估姿态。
   - **撤退**：完好率、最弱部位或较强一臂力量比低于门槛，或手里既无可施放打击式也无原版攻击手段。撤退后要恢复到返回门槛并拉开到安全距离才重新评估。
   - **对敌**：否则比较双方战力，差值不低于 `engage_margin` 即对敌。
4. **出战术**：
   - **应对**：能抢在对方起手结束前打出僵直就打断；来袭进入反应窗口后，按预计剂量与自己的下一击判断硬抗或躲。
   - **撤退时**：已到安全距离就恢复或停住；有轻身且在地面就背向跃步拉开；会机动式就先放；否则跑开。
   - **对敌时**：未受攻击时按目录顺序原地先放准备式（蓄力、轻步），免得准备期间走进轻步与跃步的最小距离；有轻身、持近战武器、在打击距离外且够跃步距离，就跃到目标身前 `assault_stand_off` 格；在打击距离内出打击式（按情境评分加权随机，种子由世界种子、实体 UUID 与乘散布常数后的决策序号混出，相邻决策不会抽到同一位置）；否则靠近。

执行时，打击、准备、恢复都走正式功法施放；移动由大脑指挥原版导航。

- 卫道士等用 Goal 的生物：大脑指挥时，原版移动与近战 Goal 暂停，目标 Goal 照旧。
- 村民：原版大脑只推进记忆与感知、跳过任务，原版恐慌不再因受击或近处敌对启动。对手存在时，无论撤退还是对敌，都按原版恐慌节奏（每 100 tick、三人门槛）召唤铁傀儡。
- 无可施放打击式但有原版攻击手段的（如掠夺者持弩）：打击交还原版攻击。

同一式连续施放失败 `failure_limit` 次后暂停 `failure_block_millis`，成功一次清零。原版近战入口在施放和双臂虚脱时拒绝，打击仍调用原版挥手。

`HumanoidTechniqueGameTest` 用「卫道士只发破门卷」的测试配置跑，保证与接管前行为一致，测试映射如下：

| 行为 | 测试 |
| --- | --- |
| 大脑施放、真实伤害、生物不扣耐久 | `realAiCastsDamagesAndKeepsAxeDurability` |
| 每十 tick 决策、无威胁与施放中不重复准备 | `brainThrottlesDecisionsAndSkipsMissingThreatAndActiveCast` |
| 0.4 倍移速、保留寻路、主动位移、PA 与近战互斥、结束恢复 | `castSlowsMovementKeepsNavigationPlaysPaAndBlocksMelee` |
| 破门动画主副手与服务端起播时刻 | `allDoorAnimationsUseSelectedHandAndServerStartTick` |
| 木门、mobGriefing 开/关 | `cleavesWoodenDoorWhenMobGriefingEnabled`、`preservesWoodenDoorWhenMobGriefingDisabled` |
| 举盾优先破盾 | `shieldingTargetPrioritizesHook` |
| 敌人包围优先横扫、旁观者不计 | `surroundedPrioritizesSweepAndBystandersDoNotCount` |
| 追赶掩门、远目标与受伤避长起手 | `pursuedPrioritizesCloseAndPressureAvoidsLongWarmup` |
| 万门仅承接、窗口前拒绝、窗口内缩短起手 | `finaleOnlySelectedInsideRealFollowUpWindow` |
| 破门起手：轻击不打断，重击僵直打断、减速 0.5 并拒绝新式 | `cleaveWarmupInterruptedOnlyByStaggeringHit` |
| 双臂虚脱避战、撤退与恢复 | `bothArmsExhaustedAvoidsCombatAndRecovers` |
| 卸载及旧 NBT 恢复不补发打击 | `unloadAndReloadClearsCastWithoutOfflineHit` |
| 死亡清理活动、姿势和限速 | `deathDuringWarmupClearsCastPoseAndMovement` |
| 按学卷表：游炁上卷、真炁上中卷及一成下卷 | `generatedVindicatorsLearnVolumesFromBrainTable` |
| 公开字节入口读取两手和唯一身体 | `inspectReportsBothHandsAndBodyIdentity` |
| 错层目标水平冲量足额、竖直分量保留、重试幂等 | `towardTargetImpulseKeepsHorizontalSpeedAcrossElevationAndReplay` |

`HumanoidBrainGameTest` 用默认学卷表和原版实体覆盖新行为：

| 行为 | 测试 |
| --- | --- |
| 无功法游炁村民被卫道士盯上时撤退而非恐慌，威胁消失后回到原版日常 | `unarmedVillagerRetreatsFromVindicatorAndResumesRoutine` |
| 学了破门上卷的真炁村民对游炁卫道士对敌叩门 | `trueQiVillagerWithUpperVolumeEngagesAndKnocks` |
| 撤退村民先轻步再背向跃步 | `retreatingVillagerLightStepsAndLeapsAway` |
| 双臂虚脱的真炁卫道士被盯上时不原地对打，先轻步再背向跃步拉开 | `exhaustedVindicatorLeapsAwayInsteadOfTrading` |
| 无威胁、疲劳达门槛时调息 | `idleFatiguedVillagerBreathes` |
| 持斧卫道士打击前先蓄力 | `axeVindicatorChargesBeforeStriking` |
| 空学卷表的掠夺者仍用原版弩射击 | `emptyTablePillagerStillShoots` |
| 无威胁村民夜里照常上床 | `idleVillagerStillSleepsAtNight` |
| 一百个无威胁村民一分钟内不准备身体会话、不记决策（健康宿主照常惰性出生身体；方阵由两格高屏障围住，闲逛不会撞上前面批次留下的生物） | `hundredIdleVillagersPrepareNoBodiesForAMinute` |
| 两只真炁卫道士互打：至少一方起手打击式，对手起手过的一方有应对；双方蓄力；同一式连续失败不到三次 | `trueQiVindicatorsDuelForThreeMinutes` |

涉及真实玩家的用例在 Basic 仓 `HumanoidBrainPlayerGameTest`：玩家打游炁村民后它撤退、打持斧真炁村民后它还手，卫道士轻身跃到六格外静立玩家面前一到两格再出招。

默认完整验证的入口由 `fabric/persistence-tests.gradle` 合并注册，包含上述功法测试和既有持久化回归。

纯规则单测：`HumanoidAssessmentTest`（姿态评估）、`HumanoidTacticsTest`（打断、躲、硬抗、突击、拉扯、准备、恢复、撤退、释放）、`StrikeGeometryTest`（覆盖与走出范围）、`HumanoidBrainConfigLoaderTest`（字段白名单、缺物种、非法概率、学卷分桶）；Fabric 侧 `HumanoidFightersTest` 覆盖空仓、半满、满仓、不同密度、空经脉与外壁库存的真元占容。
