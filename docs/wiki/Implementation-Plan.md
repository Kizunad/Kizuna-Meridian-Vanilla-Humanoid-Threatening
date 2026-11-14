# 生人威胁批次实现计划

本仓库只收活着的类人生物（生人）。支持村民、流浪商人、掠夺者、卫道士、唤魔者、女巫和幻术师；类人亡灵不在范围内。每种生人生成游炁与真炁两档变体，身体都使用 Human Addon 的 `human:adult` 完整结构和 36 条经脉。

当前实现状态：阶段 1–3 的本仓库部分已经落地。阶段 4 取消：真元属性没有被动战斗效果，只在被使用时（例如功法）才有区别。

魅惑控制器、玩家身体决策、原版动作速度与反胃自喝门禁见[魅惑控制](Charm-Control.md)。

阶段 1 的纯 Java 生成器已经落地：

- 游炁层使用左右肺、大肠、胃、脾共 8 条经脉；真炁层使用左右心、小肠、肾、膀胱共 8 条经脉；其余 20 条保持未开拓。
- 游炁变体随机开启 1–8 条游炁层经脉，真炁变体完整开启游炁层并随机开启 0–8 条真炁层经脉。每条已开经脉的内腔容量增加 1625。
- 女巫和唤魔者另有开脉保证：缺的时候按经脉标签补开内容定义里的第一条，不新增随机抽样，别的出生序列不变。女巫保证一条手经，游炁女巫再保证一条足经。唤魔者按主属性保证本行表里经至少开一条：游炁档补金、土，真炁档补火、水；真炁金、土的经在游炁层，本来就全开。唤魔者的补开在主属性抽完之后，开脉数可能多一条。凡人档都不补。
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

本仓库的 Fabric 工程通过 Framework 的 `full-assembly.gradle` 使用完整装配，装入范围由 Framework 统一装配清单决定，缺少产物明确报错。Framework、Qi-Variants、功法库、Damage、PA Mobs-Addon 与 Human 的 server/shared 在 settings 中以源码组合，编译和开发运行使用其当前输出；Human Fabric 与破门斩、基础功法等高层附属只在运行时按清单读取已构建的安装 JAR，组合构建不反向依赖本仓库。安装 JAR 嵌套本仓 server、Framework server 与 Qi-Variants 的 server/shared（Qi-Variants 没有自己的模组 JAR），装进 mods 目录即可运行。真元属性不带被动战斗效果，只在被使用时才有区别。

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
真炁自动学卷、默认与相对坐标、自然随机及采样顺序、省略档位的自然抽档、凡人档不开脉、凡人一卷不学而游炁照旧学上卷、
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

七种生人都按学卷表学卷。表在 `meridian-humanoid-brain.toml` 的 `[volumes]`，每个物种按出生档位（`mortal` 凡人、`wandering_qi` 游炁、`true_qi` 真炁）写明卷与概率，概率按 UUID 与卷 ID 确定性分桶。档位与 `body status`、生成命令一样从身体快照读回：真炁境界为真炁，游炁境界下有已开经脉为游炁，一条都没开为凡人。未写的档不学卷。默认表：

- 凡人档各物种都不写，凡人一卷不学。
- 七种生人游炁、真炁两档都学基础功法的调息、轻步、蓄力三卷。
- 卫道士游炁另学破门上卷；真炁另学上卷、中卷，下卷概率 0.1。
- 唤魔者游炁、真炁两档另学五脏诀《养》（`meridian_evoker:nourish`），真炁档再学《附》（`meridian_evoker:infuse`），概率都是 1.0。《附》的前置卷是《养》，表里排在《养》后面。功法库的卷轴掉落条件是「死者会这一卷」，玩家杀死修炼者唤魔者才可能掉《养》，杀死真炁唤魔者才可能掉《附》。整卷都学，但付费只认本行真元，唤魔者实际放得出的只有本行的式；放哪几式由五脏诀目录的生物 AI 角色决定。

学卷发生在宿主首次为实体准备会话时：显式功法请求、分档蛋/命令出生，或大脑第一次遇到威胁。已学的卷再学不变，不额外改变身体生成器随机序列。启动后第一个服务器 tick 检查表里每一卷都已安装，缺卷直接报错。

移动修正使用稳定施放 UUID，经 Technique 的 `TechniqueMovementSpeed` 换成 `MULTIPLY_TOTAL=(√倍率-1)`，冲量独立执行。
原版怪物的地面加速度跟移速属性的平方成正比，开平方后怪物的实际移速和玩家一样是倍率倍；身体 tempo 的移动倍率也经这一层。
`isCasting` 只表示限速施放，管原版攻击互斥与举武器姿态，不再表示禁止移动。收势、承接、打断、死亡与卸载移除修正。
大脑判断自己是否在施放看有无当前施放（`hasActiveCast`），不限速的式（调息、轻步、蓄力）也算。
受击打断不在类人宿主判定：原版伤害附属命中后写入功法库的僵直，与玩家同一入口；宿主受伤回调只在死亡时恢复会话。

宿主内部的只读判断（在不在施放、死没死、时钟域对不对）直接读 Store 里已提交的身体，不转协议。
`HumanoidBodyState` 按已提交的那份快照缓存协议消息和功法进度：Store 每提交一次命令就换一份新快照，缓存跟着重算。
宿主实现了功法库的 `bodyStamp`：实体已加载并认领、读档完成、会话已发布、学卷已发完且身体活着时，报出已提交版本和结清后的身体时钟；其余情况返回空，功法库照常 `inspect`。寿元会在这个时钟之前（含）用完时也返回空：真正结清会停在寿元终点并判死，时钟到不了那里，得让功法库走 `inspect` 把死亡结清出来。
时钟取世界时钟、已请求的结清目标、已结清游标三者里最晚的，与随后真正结清得到的数相同。

表现通过硬依赖 Mobs-Addon 的 `MobAnimationServer.playFrom/stop` 接入 PA，与玩家共用 Human 公开的
`HumanTechniqueAnimations` 映射并保留原始起播时刻。叩门、破门、撞门使用专属主副手片段，
命中分别对齐 25、32、33 tick；其余五式暂用 `attack_main/off`。左右惯用手由 PA 运行时镜像。
原版举斧不再是渲染要求；自动移动、举斧预备和挥砍均由 Mobs-Addon
的客户端自动层替换，显式功法动作优先，装备继续渲染。

世界打击和劈门使用功法库共享执行器的生物策略：排除友队与袭击生物，遵守 `mob_griefing`，不扣物品耐久。剂量与单次挥手做功交由 Damage，不为每个横扫目标重复做功。上、中卷费用可使用达到最低相态的任意属性库存，不能要求真炁卫道士额外持有固定的无属性游离态库存。
功法活动保留 `meridian_technique:caster` 调用身份，Framework 可据此核验万门一斩能力曲线的来源。
腿部功率由四块主要腿肌分担，不能将目录的整体功率重复施加到每块肌肉。

## 类人大脑

掠夺者的行矢卷表、臂肌成长、原版五格备用武器、射弹宿主与远程选择见[掠夺者行矢接线](Wayfarer.md)。

`HumanoidBrain` 接管物种表里 `brain = true` 的生人（七种原版默认都接管，见[物种登记](Species-Registration.md)）。`brain = false` 的物种完全不接管：不决策、不占原版控制、不改恐慌与逃跑。AI 关闭或已死亡的实体也不接管。

**盯上距离**：`[threat.notice_distance]` 按实体 ID 写原版跟踪距离（`FOLLOW_RANGE`）至少到多远，默认只列掠夺者 100 格，上限 128（原版视线判定的上限）。大脑接管实体时加一条不写进存档的临时属性修饰，让跟踪距离正好等于配置值（原版出生时的随机加成也算在内），读档后重新加；原值已够远或为零的不改。原版选敌、放下目标和寻路长度都按跟踪距离算。被放宽的实体跟这个距离内的玩家交战时，每次决策把原版无动作计时清零：原版只在 32 格内有玩家时清零，32 格外计时过 600 的每 tick 有八百分之一的机会消失，掠夺者每 tick 记 3；128 格外立即消失照旧。旧导出文件没有这张表时用 JAR 默认表，启动时提示。跟踪距离对原版所有选敌目标一样放宽，掠夺者也会在 100 格内盯上村民、流浪商人和铁傀儡。

移动指令每 tick 执行，路径每 10 tick 重算一次；导航空闲（贴身、已到、无路）时也重算，但两次寻路至少隔 5 tick。
倒排的起手计划到点之前只比较身体时钟，到点才结清并读身体。

每个实体按 ID 错相，每 `decision_interval_ticks`（默认 10）决策一次；跃步飞行中不改主意。选中的式因僵直没发出去时，僵直一结束就补一次决策，不等下一个节拍；原版近战约一秒一击、每击僵直将近一秒，固定节拍会一直落在僵直里。原版目标选择先认出了对手、大脑还没有对手时，下一个 tick 就补一次决策：原版攻击 Goal 会先朝目标走，等错相节拍最多要走上整整一个间隔（掠夺者约两格多）。决策顺序：

1. **找威胁**：候选来自上一个对手、原版目标、攻击者、正以我为目标的生物、受击记忆和村民的近处敌对记忆；遗忘距离外又看不见的不算。剩下的按 `[threat.score]` 打分，取最高分，同分取近的。
   - 每个候选的分 = Σ 权重 × 因素，因素都归一到 0–1：
     - 远近；
     - 剩余血量比，类人用身体完好率；
     - 剩余血量绝对值；
     - 记忆秒数内对我造成的伤害，线性衰减，20 点封顶；
     - 是否正以我为目标；
     - 对方威胁：境界档、持武器、刚观察到在起手；
     - 是否还算召唤物；
     - 走不走得到；
     - 是否当前对手。
   - 只读内存里的身体、原版状态和观察缓存，不给候选转身体协议。玩家的境界档用大脑上次把它当对手时的观察，没观察过按凡人档。
   - 寻路只给前两名算，每次决策最多现算一条，结果缓存 40 tick。自己会远射、远施法或投药又看得见对方时，不算走不到。
   - 防来回切：新目标要比当前对手高出 `switch_margin` 才换，换过后 `min_hold_seconds` 内不再换；当前对手死了或放下了照换。
   - 按实体 ID 覆盖写在 `[threat.score.species."<id>"]`，只写要改的键。除 `distance` 外全设 0（含门槛与持续秒数）就是只按远近选。旧导出文件没有这张表时按 JAR 默认运行，启动时列出缺的键。
   - 带主人的召唤物（`OwnableEntity`：恼鬼、驯服的狼、别的模组的召唤物）先换成最终主人再打分，召唤物打我、盯我都记在主人头上，主对手留在主人身上。主人不在、是自己的同伴，或者又远又看不见时，还是召唤物自己，打分时扣 `summon` 分。
   - 召唤物贴到原版近战距离内、主人又走不到（现算的路到不了主人跟前）时，才把这只召唤物当对手顺手打。自己在空中（跃步、被击退）时算不了路，不算走不到。
   - 玩家造的铁傀儡原版不记是谁造的，算不到人头上，仍按它自己算。
   - 原版受击记忆不分敌我；记着的是同伴时算误伤，不算威胁，和登记攻击者时「同伴误伤不记」一致。同伴真以我为目标时，照旧从「正以我为目标的生物」里取到。
2. **无威胁**：交还原版控制，不准备身体、不记决策。已有会话且会恢复式的实体例外：疲劳达门槛就调息。
3. **有威胁**：读身体和对手的施放预报，评估姿态。
   - **撤退**：完好率、最弱部位或较强一臂力量比低于门槛，或手里既无可施放打击式也无原版攻击手段。撤退后要恢复到返回门槛并拉开到安全距离才重新评估。
   - **对敌**：否则比较双方战力，差值不低于 `engage_margin` 即对敌。
4. **出战术**：
   - **应对**：能抢在对方起手结束前打出僵直就打断；来袭进入反应窗口后，按预计剂量与自己的下一击判断硬抗或躲。
   - **撤退时**：先检查落脚点能否容纳实体、脚下是否有碰撞支撑，再由原版寻路器验证可达性；失败不再生成墙外坐标。优先寻找较长退路，沿用尚未走完的可达路径，已到安全距离就恢复或停住，否则以轻身、机动式或步行拉开。无退路时优先用现有打击式反击，随后考虑满足目录距离的恢复，原版攻击者交还攻击 Goal 自卫，会防守腿法的生物转身迎敌（见下条）；双臂虚脱仍禁止普通反击与原版自卫。原版攻击者受困后只有找到通往安全距离的退路才恢复逃跑，避免刚向前一步又退回墙角；腿力过门槛、出得了防守腿法且至少一式冷却已过、眼下用得上的生物（扫堂只在被围时算，被围时正面两式不算；对手贴在身后 2 格内、转身来不及时只算尥蹶）按安全距离找退路，找不到能退到 `safe_distance` 的可达路线就算没退路，转身迎敌，不再靠短距离反复拉开；学会了防守腿法但做不了功、几式都在冷却或眼下用不上的，照旧找较短退路，以轻身、机动式或步行躲开拉距离；腿力没过门槛的只步行，不跃步也不放机动式；连短退路都没有时，找不比现在离对手更近的可达点贴墙横着让开；横让的点也没有、自己又在墙角（东西、南北两个方向都在 2 格内到墙）而对手还在 3 格以外时，沿墙走到相邻的另一个角，那个角离对手也要在 3 格以外；这些都找不到才算没退路；无防守腿法、无原版攻击能力的村民也可尝试较短退路。
   - **商人防守**：弹腿、正蹬、尥蹶、扫堂已经通过腿力、费用、冷却和处境筛选时，撤退姿态仍可出手；不会因受困忽略腿伤或虚脱门槛。强敌可以打断或躲开反击。会其中一式、腿力过门槛、做得了功且冷却已过，却没退路时主动反击：对手进了打击式距离就出腿；够不着时，对手远于 `cornered_stand_distance`（默认 2 格）就迎上去，近了站定并把身体转向对手（原版视线控制只转头，身前、身后与打击扇区按身体朝向算），等弹腿、正蹬的身前条件成立；已在安全距离外则原地不动。学会了却因腿力、做功、费用、冷却或对手贴在身后（又不会尥蹶）暂时出不了腿时，照旧躲开拉距离，腿力回到门槛、冷却好了、转得过身再迎上去出腿；其中腿力没过门槛时只走开或横让，不跃步、不放轻步，闪避也只走出范围、不跃步：跃步要付腿上的功，越跃腿力越回不来；对手在身后但离得远时先转身面对再出腿；连短退路都没有时贴墙横着让开，横不开又在墙角、对手还在 3 格以外时沿墙换到另一个角，不站着等腿法就绪；这些都找不到才原地转身面向对手等着。闪避照旧，躲开后接反击。开阔地能退到安全距离时照旧撤退和跃步拉开。旧导出配置缺这个键时按默认值运行，启动日志列出缺的键。
   - **绊倒速度**：扫堂命中后清空水平速度，向上速度固定为 2 米/秒；玩家与类人生物宿主使用相同语义。
   - **躲避出口**：墙外或不可达出口不算可躲；步行时间按实际路径长度估计，无出口不再当作零毫秒。
   - **对敌时**：未受攻击时按目录顺序原地先放准备式（蓄力、轻步），免得准备期间走进轻步与跃步的最小距离；有轻身、持近战武器、在打击距离外且够跃步距离，就跃到目标身前 `assault_stand_off` 格；在打击距离内出打击式（按情境评分加权随机，种子由世界种子、实体 UUID 与乘散布常数后的决策序号混出，相邻决策不会抽到同一位置）；否则靠近。

执行时，打击、准备、恢复都走正式功法施放；移动由大脑指挥原版导航。近战打击（武器打击、腿法）与行矢在出手段结束前站定并面向对手，首击距离由起手时的站位保住；各式的打击与放箭都落在出手段内。收式阶段照当前指令移动，速度受这一式声明的施放移动倍率限制，和玩家施法时一样；对手跳出打击距离也不提前收式。

- 卫道士等用 Goal 的生物：大脑指挥时，原版移动与近战 Goal 暂停，目标 Goal 照旧。
- 村民：原版大脑只推进记忆与感知、跳过任务，原版恐慌不再因受击或近处敌对启动。对手存在时，无论撤退还是对敌，都按原版恐慌节奏（每 100 tick、三人门槛）召唤铁傀儡。
- 无可施放打击式但有原版攻击手段的（如掠夺者持弩）：打击交还原版攻击。

读候选时，每一式除了绑定、持物和真元费用，还要过功法库的做功预检 `TechniqueAvailability.workable`：组织和器官做不了这一式的起手做功或任一段时间线做功（比如长局里腿肌累了，撞门、掩门要的功出不来）就不进候选，女巫和受控玩家的选式也一样。同一式连续施放失败 `failure_limit` 次后暂停 `failure_block_millis`，成功一次清零。原版近战入口在施放或大脑控制撤退时拒绝；受困并交还原版自卫后允许出手，伤害仍受真实肌力约束。

`HumanoidEnclosedArenaGameTest` 在带地面、四壁和顶棚的 8×8 石室内开启真实 AI，覆盖游炁与真炁的卫道士／掠夺者交战、两档商人对持斧真炁追兵的防守尝试，以及对徒手凡人追兵的腿法命中。每局观察 1200 tick 或直到一方死亡；双方存活且连续 400 tick 无伤害进展时报告导航、决策、身体条件和腿力。一方最后一条决策在拉开、闪避或在墙角转身等着，另一方最后一条决策正对着它接近、突击、打击、打断、交还原版攻击或做接敌准备（保持跃步带或跟随），或者正在施放、移动指令仍是跟随，而且追的一方最近 1 秒里水平挪了至少 1 格、或自己把到对方的距离缩短了至少半格的 tick 只停表、不清零：躲开追打是有效防守，不算僵持；对手不追、原地跟着转、贴着墙磨或双方都不动仍计静默，其中决策像在追却没挪动的 tick 另计入采样日志的 `idleChase`。观察器不补血、不补真元、不代施放，也不在开场后传送实体。独立调试可使用 `-PreproMode=arena -PgametestRunDir=<新的运行目录>`；完整回归也包含这些场景。按名称筛选可加 `-PgametestFilter=*:*exhaust*` 等资源 ID 通配符。

开阔地撤退与跃步测试使用 40×40 的 `retreat` 场地，实体从中央出生；原版 8×8 测试屏障会把逃跑用例变成受困用例。短退路测试检查实际拉开距离，允许到达退点后导航结束。自然行矢射击测试在开始前补足真元，之后仅观察真实 AI；旧基础卷配置仍保留原版弩射击验收。

`HumanoidTechniqueGameTest` 用「卫道士只发破门卷」的测试配置跑，保证与接管前行为一致，测试映射如下：

| 行为 | 测试 |
| --- | --- |
| 大脑施放、真实伤害、生物不扣耐久 | `realAiCastsDamagesAndKeepsAxeDurability` |
| 每十 tick 决策（原版刚认出目标时下一个 tick 先补一次）、无威胁与施放中不重复准备 | `brainThrottlesDecisionsAndSkipsMissingThreatAndActiveCast` |
| 0.4 倍移速（属性 √0.4）、保留寻路、主动位移、PA 与近战互斥、结束恢复 | `castSlowsMovementKeepsNavigationPlaysPaAndBlocksMelee` |
| 同一施放倍率下，怪物和玩家一秒走的距离占原速的比例相同 | `castWalksSameShareOfFullSpeedDistanceAsPlayer` |
| 破门动画主副手与服务端起播时刻 | `allDoorAnimationsUseSelectedHandAndServerStartTick` |
| 木门、`mob_griefing` 开/关 | `cleavesWoodenDoorWhenMobGriefingEnabled`、`preservesWoodenDoorWhenMobGriefingDisabled` |
| 举盾优先破盾 | `shieldingTargetPrioritizesHook` |
| 敌人包围优先横扫、旁观者不计 | `surroundedPrioritizesSweepAndBystandersDoNotCount` |
| 追赶掩门、远目标与受伤避长起手 | `pursuedPrioritizesCloseAndPressureAvoidsLongWarmup` |
| 万门仅承接、窗口前拒绝、窗口内缩短起手 | `finaleOnlySelectedInsideRealFollowUpWindow` |
| 破门起手：轻击不打断，重击僵直打断、减速 0.5（属性 √0.5）并拒绝新式 | `cleaveWarmupInterruptedOnlyByStaggeringHit` |
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
| 腿肌出不了功的真炁卫道士：撞门、掩门绑得上、付得起真元，但做功预检不过、不进候选，叩门照旧在候选里 | `spentLegsKeepLegWorkOutOfCandidates` |
| 无威胁、疲劳达门槛时调息 | `idleFatiguedVillagerBreathes` |
| 持斧卫道士打击前先蓄力 | `axeVindicatorChargesBeforeStriking` |
| 空学卷表的掠夺者仍用原版弩射击 | `emptyTablePillagerStillShoots` |
| 无威胁村民夜里照常上床 | `idleVillagerStillSleepsAtNight` |
| 一百个无威胁村民一分钟内不准备身体会话、不记决策（健康宿主照常惰性出生身体；方阵放在 10×10 的测试结构里，由 GameTest 的屏障围住，闲逛不会撞上前面批次留下的生物） | `hundredIdleVillagersPrepareNoBodiesForAMinute` |
| 两只真炁卫道士互打（原版同伴，互为目标；出生后先空等原版近战查过两次再互设目标，不让原版抢在大脑前开砍）：至少一方起手打击式且至少一方被打击式打中，或双方都撤退；双方都起过手时各自有应对；双方蓄力；同一式连续失败不到三次；每局走的分支（交手 / 一方撤退 / 双方撤退）写进 GameTest 日志，不断言 | `trueQiVindicatorsDuelForThreeMinutes` |
| 不动的唤魔者在 6 格外的平地上，它的两只恼鬼贴身咬真炁村民：村民的对手一直是唤魔者，没换成过恼鬼 | `vexBitesKeepTheEvokerAsTheOpponent` |
| 不动的掠夺者在 6 格外误伤真炁卫道士一下，原版受击记忆记着它：之后 3 秒卫道士没把同伴当过对手，也没以它为目标 | `allyStrayHitDoesNotStartAFight` |
| 两只不动的尸壳都盯着真炁卫道士，近的 3 格、远的 6 格，远的先打了它 8 点：卫道士第一次决策就选远的，之后 3 秒没换过（只按远近时选近的） | `scoredThreatPrefersTheFartherOneWhoHurtMe` |
| 两只不动的尸壳各打过真炁卫道士一下，之后每秒轮流盯它：10 秒内换对手最多一次（去掉惯性和门槛时换了 9 次） | `scoredThreatDoesNotFlipBetweenEvenThreats` |

涉及真实玩家的用例在 Basic 仓 `HumanoidBrainPlayerGameTest`：玩家打游炁村民后它撤退、打持斧真炁村民后它还手，卫道士轻身跃到六格外静立玩家面前一到两格再出招。

默认完整验证的入口由 `fabric/persistence-tests.gradle` 合并注册，包含上述功法测试和既有持久化回归。

## 唤魔者和原版法术

唤魔者学了五脏诀《附》以后，大脑放本行附伤式，原版尖牙、恼鬼照旧放，命中时带上附行（附行由 Vanilla Damage 结算）。这一节是 Threatening 这边的接缝；五脏诀各式的数值和 AI 角色见五脏诀仓的 `Integration.md`。

- **补放门槛。** 准备式原来只看冷却。目录给准备式设了 `refresh_before_expiry_millis`、这一式又带 `elemental_infusion` 效果时，大脑另查身上附行的剩余时间，剩不到这个值才放。换位式照旧查轻身剩余，别的准备式不变。剩余时间从本轮已读的身体算（`TechniqueInfusions.remainingMillis`），不另查一次。
- **先后。** 附伤五式排序 0，目标 8 格外放；厚土、归水排序 1，目标 12 格外、没挨打时放，排在轻身（2）前。蓄力也是 1，唤魔者没有近战武器，不蓄力。
- **和原版法术互斥。**
  - 正在施功法时，原版尖牙、恼鬼不开始（`SpellcasterSpellGoalMixin` 拦 `SpellcasterUseSpellGoal.canUse`）；原版施法期间不起功法（`HumanoidActions.cast`）。原版法术的冷却照走。
  - 修炼者唤魔者换了新目标、大脑还没接上它时，原版法术先等，最多一个决策间隔（`decision_interval_ticks`）。原版在设目标后一两 tick 就会起，大脑要到下一次决策才看见这个目标，不等的话会先唤恼鬼。等满就放行，大脑选了别的威胁也不会一直压着原版。
- **变羊。** 修炼者（游炁、真炁）不变羊，凡人照原版（`EvokerWololoGoalMixin`）。
- **声音和粒子。** 唤魔者起附伤式时在 0 ms 播原版 `entity.evoker.prepare_wololo`，归敌对生物类别。起手到附行生效前，服务端每 tick 在两只手各发一颗本行颜色的 `entity_effect` 粒子：金白、土黄、火红、水黑、木青。厚土、归水不是附伤式，不播也不发。玩家放五脏诀不走这里。
- **卷顺序。** 启动时检查每档学卷表：一卷的前置卷必须在同一张表里、排在它前面，否则服务端启动失败，报出哪一卷排在它的前置卷前面，或者缺了哪一卷。
- **受控玩家。** 五脏诀十五式的打击选式权重都是 0，受控玩家的选式只按这个权重抽，不会自动放这几式。
- **腿肌。** 厚土、归水只改腿肌耐力和恢复，不改动作速度；生物走路不耗腿部做功，所以两式不影响原版走动，只影响用腿做功的式（轻身）和按疲劳判断的门槛。
- **保持距离。** 施法型物种（`SpellcasterIllager`）学了会补放的附伤式时，「接敌前准备」（准备决策和施放准备式期间）总是保持距离带。带的下界取这些式里最远的起手下限（现在是 8 格）再加 0.5，宽 4 格，所以唤魔者站在 8.5–12.5 格：近了退开，远了走近，带里停下（`HumanoidActions.casterRefreshGate`）。以前没有打击式，带只有 0.5–4.5 格，唤魔者贴到 3 格，第一次附伤之后就补不上。准备以外的时间照旧交还原版。

`HumanoidEvokerGameTest` 用 40×40 的 `retreat` 场地，开真实 AI 的唤魔者对一头不动、1000 血的牛（`setTarget`），逐 tick 记施放、原版施法、命中和收到的包：

| 行为 | 测试 |
|---|---|
| 真炁火唤魔者先放灼火，原版法术在它之后，两者不重叠，原版法术命中带火行附伤（原版随机挑尖牙或恼鬼，哪个都算） | `fireEvokerInfusesBeforeItsSpellsAndTheyBurn` |
| 唤出的恼鬼命中也带附伤 | `summonedVexesCarryTheInfusion` |
| 起附伤式时念一次 `prepare_wololo`（敌对生物），起手期间手上有火红粒子，附行生效后停 | `infusionCastSaysPrepareWololoWithHandParticles` |
| 预置一段剩 10 s 的附行：剩到 3 s 以内才补放；唤魔者不钉在原地，补放施放期间离目标不进 8 格 | `refreshWaitsUntilTheInfusionIsExpiring` |
| 游炁土唤魔者没有《附》，尖牙不带附伤 | `wanderingEarthFangsCarryNoInfusion` |
| 心被打坏（HP 0）后不放灼火，照原版唤尖牙、恼鬼，命中不带附伤 | `destroyedHeartFallsBackToVanillaSpells` |
| 游炁土唤魔者对 13 格外的目标第一式放厚土（不先轻身），不念咒、不冒粒子 | `earthEvokerStrengthensLegsBeyondTwelveMeters` |
| 目标在 8 格内时不起附伤式 | `noInfusionWithinEightMeters` |
| 凡人唤魔者把蓝羊变红，修炼者不变，也不起原版施法 | `onlyMortalEvokersWololo` |

`HumanoidEvokerBirthGameTest` 查出生：真炁金、土、火、水唤魔者学到《附》，本行附伤式绑得上、付得起也做得了；游炁金、土付得起金臂、厚土。日志记本行真元够放几次。

纯规则单测：`HumanoidAssessmentTest`（姿态评估）、`HumanoidTacticsTest`（打断、躲、硬抗、突击、拉扯、准备、恢复、撤退、释放）、`StrikeGeometryTest`（覆盖与走出范围）、`HumanoidBrainConfigLoaderTest`（字段白名单、缺物种、非法概率、未知档位、凡人默认空档、学卷分桶、盯上距离表缺省回退与越界）；Fabric 侧 `HumanoidFightersTest` 覆盖空仓、半满、满仓、不同密度、空经脉与外壁库存的真元占容。

## 生人见闻录

《生人见闻录》是一本原版成书，给不看代码的玩家讲生人看得见的举动和应对：长招起手能被重击打断、伤重撤到 12 格外调息再回来、各物种的招式和距离、掠夺者百格远射。只写看得见的动作和距离，不写内部字段。

- **来源。** 新手图书管理员的交易：5 绿宝石加 1 本书换一本，每单 12 次。交易定义在 `data/meridian_humanoid/villager_trade/librarian/1/emerald_and_book_field_notes.json`。原版交易组 `minecraft:librarian/level_1` 由本仓覆盖成指向本仓标签 `#meridian_humanoid:librarian/level_1`，数量（2 单）和随机序列照原版；这个标签先引用原版标签 `#minecraft:librarian/level_1`、再加这本书。原版标签开没开实验性的 `trade_rebalance` 都能用：那个实验包把原版标签整份换掉（`replace`），直接往原版标签里加的条目会被冲掉，引用原版标签则跟着换成实验那一份。新手图书管理员从这一组里抽两单，所以不开实验时大约一半的图书管理员会卖；打掉讲台重放可以重抽。原版改了这一组的数量或随机序列，要同步改覆盖文件。
- **文本。** 书名用 `minecraft:item_name` 的翻译键 `book.meridian_humanoid.field_notes`，成书标题和作者留空，所以界面上只显示书名、不显示作者；18 页各是 `book.meridian_humanoid.field_notes.page.<n>` 的翻译键，简中是全文，英文是压缩版（每页不超过原版书页能放下的字数）。书里存的是翻译键，由客户端按当时的语言显示，所以改了语言文件，已经换出的书也显示新文字。
- **和代码对齐。** 书里的距离和时长来自本仓大脑配置与功法目录（卫道士 4–24 格蓄力、商人墙角 2 格和 3 格、掠夺者百格发现和远射站位、女巫 8 格服药和 4–14 格掷引、唤魔者 8 格附行和 12 格厚土归水、召唤物算到主人头上）。这些值改了，要同步改两种语言的页。

| 行为 | 测试 |
|---|---|
| `TradeSets.LIBRARIAN_LEVEL_1` 里有这本书，原版标签里的交易一单不少（GameTest 服务端开着 `trade_rebalance`，查的是实验那一份）；照原版村民的取货上下文换出一本成书，价钱是绿宝石加书，书名和每页按页序引用翻译键 | `HumanoidFieldNotesGameTest.noviceLibrarianSellsFieldNotes` |
| 交易里引用的书名和每页的键，简中、英文都有且不空 | `FieldNotesTextTest.everyPageHasTextInEveryLanguage` |
| 简中书名和正文里没有分数、倍率、按键、界面这些机制说法，也没有英文字母 | `FieldNotesTextTest.chineseTextUsesNoMechanicWords` |
