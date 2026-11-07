# 掠夺者行矢接线

本页记录生人宿主的行矢接线。功法定义、费用、时长、射击参数和承接表由
`meridian_wayfarer` 正式目录提供；身体能力与扣费由 Framework 和功法库负责。
射弹实体使用 Human 的公开执行器，不在生人宿主复制箭、烟花或弹道实现。

## 出生与学卷

`meridian-humanoid-brain.toml` 保留基础三卷，并为掠夺者增加以下分配：

| 出生档位 | 行矢卷 | 备用弓 | 六块臂肌初始耐力成长 |
| --- | --- | --- | --- |
| 凡人 | 无 | 无 | 0 |
| 游炁 | 弩卷，100% | 无 | 0.20 |
| 真炁 | 弩卷、弓上卷，100%；弓下卷，10% | 一把 | 0.35 |

学卷概率继续按实体 UUID 和卷 ID 确定性分桶。同一实体再次准备会话不会重抽。
臂肌成长使用 `human:arm_muscle` 标签，覆盖两侧肱二头肌、肱三头肌和肱桡肌；
待巩固负荷为 0。成长是固定值，不消耗身体生成随机流。

物种配置新增 `wandering_qi_arm_training_growth`、`true_qi_arm_training_growth`，
二者必须为有限的 `[0,1]` 比例。未配置物种取 0；凡人出生挂点始终取 0。
腿部成长保持原有独立配置。当前 Human 已声明臂肌训练能力，直接写入正式组织状态。

已有配置不会自动追加新版字段。更新实例时须备份并合并掠夺者的行矢学卷与两项臂肌成长，
保留其他配置。重启后已有身体在重建宿主会话时补学配置中的卷；不会追补出生臂肌成长或备用弓。
需要验证完整出生配置时，应使用新生成的掠夺者，不能将已有身体视为新默认值样本。

## 备用武器和存档

`HumanoidWayfarerWeapons.equipAtBirth` 在自然新身体与指定档位出生时调用。
真炁掠夺者将弓放进原版五格 `SimpleContainer` 的首个空槽；已有弓不重复补发，
库存满时不覆盖任何物品。恢复已有身体不会调用出生补发。

候选阶段只序列化库存预览：保留原另一手和全部真实持物事实，交给
`TechniqueHeldItems.find(rules, items, definition)` 检查式级武器与弹药规则。
随后调用 `TechniqueBinder.bind(body, definition, selectedHand, mainHandSide)`，按预览选定手
与同次 inspect 的解剖主手侧绑定组织和校验费用，避免副手换兵仍扣到错误手臂。
选中后才交换库存格和装备槽中的原 `ItemStack` 实例。副手原持弓弩时仍换副手。
真实施放被拒或抛出异常时还原同一对物品；成功后保留新武器，不立即换回。
交换不改变附魔、耐久、自定义名称和 `CHARGED_PROJECTILES`。

26.2 原版核对依据是本机 Loom 的 `minecraft-merged-deobf-26.2.jar`：

- `Pillager` 构造器创建五格库存，`getInventory()` 返回该容器。
- `Pillager.addAdditionalSaveData/readAdditionalSaveData` 调用 `InventoryCarrier` 的读写入口，
  使用 `Inventory` 项目列表保存物品组件，本仓不另存备用武器。
- `Pillager`、`AbstractIllager`、`Raider` 没有整包库存死亡掉落实现；
  `InventoryCarrier` 只提供拾取和读写。仍在五格库存里的备用弓不会自动掉出。
- 换到手上的武器走 `Mob.dropCustomDeathLoot` 的装备槽概率、附魔和耐久处理。
  本仓不改掉落概率，不保证弓或弩必掉。

## 事实和选择

同一轮决策采集一次 `HumanoidWayfarerFacts.Target`：水平距离、视线、水平速度（米/秒）、
相对水平速度是否逼近，以及八格内前方 ±45° 的敌人数。
逼近使用双方相对速度与目标方向的点积，自己站定而敌人冲来同样成立。
旁观、创造玩家、普通同伴、扇面外实体不计入横风人数。

| 情境 | 候选式 |
| --- | --- |
| 大于 16 格且目标水平速度为 0 | 满月、跪射 |
| 8–16 格 | 连珠、连弩 |
| 小于 8 格且扇面至少两个敌人 | 横风 |
| 不超过 3 格，当前持弓 | 弓梢 |
| 不超过 3 格，当前持弩 | 横风 |
| 目标逼近且身体完好率低于一半 | 回马 |
| 保有目标实体但视线被挡 | 箭雨 |
| 满月或箭雨的有效承接窗口 | 天地同至 |

上述条件只缩小已学候选，最终仍须通过绑定、可负担费用、冷却、持物、距离和承接窗口。
拉空弓始终被排除。对手事实传入功法库的 `CombatContext`，备用武器集合来自真实库存；
Mob 的弹药事实为可用。距离档为 `<8`、`8–16`、`>16`。

普通大脑仍负责准备、躲避、硬抗与撤退。可施放回马时优先拉开，但不覆盖双臂虚脱、
躲避或硬抗；施放中的行矢在决策周期继续检查承接窗口。两臂有效力量低于既有门槛时避战。

## 射弹与互斥

每个实体会话持有 `HumanProjectileEffects(entity, hostilePredicate)`：

- `target_lock` 交给 `lockTarget`，`launch_projectile` 交给 `launch`。
- 效果请求和效果身份由现有宿主回执去重，重复提交不重复出箭。
- 生物使用无限弹药、不扣武器耐久，生成的箭不可拾取；保留弩原有上弦组件。
- 拉弦系数、主副手解剖映射、横风镜像、弹道及附魔由同一个 Human 执行器处理。
- 收势/取消的 STOP 与 UNLOCK 调用 `finish`；实体卸载丢弃会话及临时锁定。

弓梢复用 `TechniqueWorldEffects`，完整主副手伤害 ID 从目录的 `WeaponStrike` 读取。
共享执行器保留原构造器；没有显式 ID 的既有破门目录沿用原命名空间路径。

行矢起手停止原版物品使用和导航，清除原版 charging 标志，不清上弦组件。
施放期间拦截 `LivingEntity.startUsingItem` 的生物弓弩入口与 `Pillager.performRangedAttack`，
避免原版再射一发。每 tick 的移动适配保持站位和注视，直接冲量不被清零。
表现仍交现有 PA/Mobs 公共映射；本仓没有新增客户端姿势或模型 Mixin。

高优先级移动 Goal 与原版弩 Goal 共用 MOVE/LOOK 控制。准备和行矢施放时由大脑接管；
没有当前打击候选时，APPROACH 对有原版攻击能力的生物释放控制，让原版完成上弦和射击。
单纯缺少行矢学卷不会命中行矢射击拦截；撤退、虚脱和目标有效性仍遵循原战术规则。

## 验证

纯 Java 回归覆盖六肌成长、凡人/其他物种零成长、待巩固零、配置边界、
不改变其余随机出生结果，以及三档卷表与 10% 概率配置。

| GameTest 类 | 覆盖 |
| --- | --- |
| `HumanoidWayfarerGameTest` | 三档学卷与备用弓；主副手交换；组件保留；原版存档往返 |
| `HumanoidWayfarerAiGameTest` | 距离与视线；禁止训练；相对逼近；起手拒绝还原；换兵及天地同至承接 |
| `HumanoidWayfarerDecisionGameTest` | 扇面敌我；真实大脑半血回马；双臂虚脱撤退 |
| `HumanoidWayfarerProjectileGameTest` | 多重射击、无限弹药/耐久、不可拾取与幂等；副手拉弦；原版互斥与卸载 |
| `HumanoidWayfarerNaturalAiGameTest` | 游炁/真炁自然施放并生成箭；基础三卷与零臂肌成长仍自然射弩；行矢结束后恢复原版射击 |

五类均登记到默认完整回归，使用独立长场地 `meridian_humanoid_test:archery`。
自然 AI 回归开启实体 AI，只观察真实 tick、权威施放与射手所有的新增箭，不直接请求 cast 或 launch。
运行前检查全机没有其他 Gradle；每次选一个尚未使用的运行目录：

```sh
ps -ewwo pid,args | rg "[g]radle-wrapper\\.jar"
bash "gradlew" --no-daemon test build :fabric:compileGametestJava --console=plain
bash "gradlew" --no-daemon :fabric:runGametest \
  -PgametestRunDir=build/gametest-wayfarer-l4-20261103-b --console=plain
python3 "scripts/check_docs.py"
git diff --check
```

`gametestRunDir` 指定运行目录时不删除任何旧世界。未指定时保留既有回归脚本行为。
完整装配必须包含正式行矢目录及其物品/伤害标签，不能以测试替身绕过缺失的附属。

### 2026-11-03 联调结果

分段做功不需要 Framework 未来偏移字段。Technique 在每个
`TechniqueEffect.work` 开始事件提交当前身体的一段费用，宿主回执成功后才交付同刻射击；
已开始段不退，取消、死亡或下线后的未开始段不扣。按手位绑定入口已接入
`HumanoidRepertoires.ready`，候选与真实起手使用同一选手和绑定顺序。

首次完整装配发现 `archery.snbt` 缺少调色板，16 条行矢 GameTest 只在 tick 0 报结构加载失败；
补齐场地夹具后已重新运行完整装配，最终退出码和 16 条用例的 XML 结果记录在交接报告中。
