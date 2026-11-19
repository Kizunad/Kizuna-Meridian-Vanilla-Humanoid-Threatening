# 物种注册

Threatening 内置七种原版生人。其他模组可以在初始化时登记自己的实体类型，登记后：

- 由 Threatening 认领为身体宿主；
- 身体由物种提供的生成器或内置人体生成器创建，随实体 NBT 存档；
- 伤害、蔓延、疲劳都走与原版生人相同的 Damage 与 Human 管线；
- 健康宿主协议、功法宿主照常可用。

登记物种不需要复制宿主代码。

## 入口

| 类 | 方法 | 用途 |
|---|---|---|
| `HumanoidSpeciesHooks`（fabric） | `registerAll(String toml, SpeciesBodyRules rules, Predicate<LivingEntity> accepts, List<AddonBundle> bundles)` | 一次提交共用这些规则的物种、个体认领条件和内容包，返回该表登记的物种列表 |
| `HumanoidSpeciesDefinitions`（server） | `List<HumanoidSpecies> registerAll(String toml)` | 解析一张物种表并逐条登记；整张表校验通过后才登记，不会只生效一半 |
| | `List<HumanoidSpeciesDefinition> parse(String toml)` | 只解析不登记 |
| `HumanoidSpecies`（server） | `static HumanoidSpecies register(HumanoidSpeciesDefinition)` | 直接登记一条定义 |
| | `static Optional<HumanoidSpecies> fromEntityId(String)` | 内置与登记物种都能查到 |
| | `static List<HumanoidSpecies> values()` | 只含内置七种 |
| | `static List<HumanoidSpecies> registered()` | 只含登记物种，按实体 ID 排序 |
| | `Optional<HumanoidSpeciesDefinition> definition()` | 内置物种为空 |
| | `boolean brainManaged()` | 内置物种为真；登记物种取定义里的 `brain` |
| `HumanoidSpeciesBirths`（fabric） | `static void birth(LivingEntity, HumanoidTier)` | 实体创建、就位后，加入世界前调用，按指定档位出生身体 |
| `HumanoidSpawns`（fabric） | `static void registerPlacement(HumanoidSpecies, Placement)` | 登记物种的 `/meridian humanoid spawn` 放置钩子；钩子创建实体、调用 `birth` 后加入世界并返回实体，内置物种与重复登记被拒绝 |
| `HumanoidTier`（server） | `MORTAL` / `WANDERING_QI` / `TRUE_QI`，`static HumanoidTier of(BodySnapshot)` | 出生档位，可从身体快照读回 |
| `HumanoidTiers`（fabric） | `static Optional<HumanoidTier> of(LivingEntity)` | 查一个在世界里的生人是哪一档；身体还没出生的按需出生。不是已认领的生人、世界没接好、区域服务这一刻不可用时为空，下一 tick 再问 |
| `HumanoidDuties`（fabric） | `standGround(LivingEntity, boolean)`、`standsGround(LivingEntity)`、`guardVillage(LivingEntity, boolean)`、`guardsVillage(LivingEntity)`、`trainArms(LivingEntity, double, double)`、`ensureOpened(LivingEntity, String)` | 给生人挂、撤「职守」：不退；守村（多认声望极差的玩家和袭击队伍，遭袭击时到钟边守着）；任职时把两臂肌肉的训练成长抬到给定值；某类经脉一条都没开时保底开一条。见计划文档「职守」 |
| `HumanoidTargets`（fabric） | `static void registerDowned(Predicate<LivingEntity>)` | 登记一种「对手倒地」的判断，大脑选式时据此给出 `TARGET_DOWNED`；功法库的倒地僵直不用登记。在模组初始化时调用 |

**调用时机**：登记必须在世界加载前完成，例如在 `ModInitializer.onInitialize` 里。

`SpeciesBodyRules.BirthFactory` 收到实体 UUID、区域库存及来源 ID、结算时刻、档位和随机源。返回快照的 ID 须等于 `request.bodyId()`，时刻须等于 `request.now()`，模板 ID 须与物种表一致，版本须已安装。缺省生成器沿用人体出生逻辑。

`SpeciesBodyRules.DeathVerdict` 收到扣除本击部位、组织和器官 HP 后的快照与完整的人体伤害结果，返回致死判断。缺省时采用表中 `death_rule`。内容包在服务端启动时与人体和真元变体包一起安装，安装后再交会报错。

宿主按实体类型缓存物种，每次查询都对命中的物种核对当前个体的 `accepts`。撤销认领时，已有身体、真元、回执和结算时刻随实体封存在 `meridian_humanoid` 中；健康和功法宿主返回未绑定，类人大脑交还控制。重新认领后沿用这具身体，从保存的时刻结算。从未出生过身体的排除个体没有这个存档键。

**拒绝规则**：

- 同一实体 ID 只能登记一次；
- 不能覆盖内置七种；
- 错误文本以「类人物种登记无效」开头。

## 物种表

```toml
[species."meridian_humanoid_dummy:dummy"]
template = "human:adult"       # 假人沿用人体生成器
default_tier = "wandering_qi"  # 放置与命令的默认档位：mortal、wandering_qi、true_qi
death_rule = "never"           # 可省略，默认 human
brain = false                  # 可省略，默认 true

[species."meridian_humanoid_dummy:dummy".profile]
true_qi_base_probability = 0.0 # 自然出生抽到真炁的基础概率
opening_count_multiplier = 1.0
fill_multiplier = 1.0
wall_fill_multiplier = 1.0
arm_opening_weight = 1.0
foot_opening_weight = 1.0
```

| 键 | 必填 | 取值 |
|---|---|---|
| `template` | 是 | 身体模板 ID，使用其他模板时须提供出生生成器 |
| `default_tier` | 是 | `mortal`：游炁境界，一条经脉都不开。<br>`wandering_qi` / `true_qi`：与原版生人相同 |
| `death_rule` | 否 | `human`：部位总 HP 低于两成，或头、颈、胸任一个归零，或心归零即死亡，与玩家相同。<br>`never`：损伤照常分配，只去掉致死结论，部位可以全部归零 |
| `brain` | 否 | `true`：由类人大脑接管。<br>`false`：大脑完全不接管，不决策、不占原版控制、不改恐慌与逃跑 |
| `profile` | 是 | 出生画像，字段与 `meridian-humanoid.toml` 中 `[species.*]` 相同 |

## 各项来源

- **出生画像**：
  - 登记物种的画像来自它自己的定义；
  - 内置七种的画像仍来自 `meridian-humanoid.toml`；
  - 该配置文件只接受内置七种的实体 ID。
- **自然出生**：没有指定档位的实体首次需要身体时，按画像的 `true_qi_base_probability` 和区域浓度在游炁、真炁之间抽档，不会抽到凡人。
- **命令出生**：`/meridian humanoid spawn` 补全登记物种的实体 ID；省略档位时用物种表的 `default_tier`；
  登记了放置钩子时由钩子放置并收到命令源朝向，否则按原版生物创建（实体须是 `Mob`）。写法见 [Framework 指令页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki/Commands)。
- **学卷**：
  - `meridian-humanoid-brain.toml` 的学卷表可以另列登记物种的实体 ID，按出生档位 `mortal`、`wandering_qi`、`true_qi` 分档写；
  - 未列出的物种或未写的档不学卷；`default_tier = "mortal"` 出生的身体查 `mortal` 档，不沿用游炁档。
  - 功法包可以随模组带一份学卷片段，在自己的 `fabric.mod.json` 里用 `custom` 的 `meridian_humanoid:volumes` 指过去，启动时并进这张表；写法见计划文档「功法宿主」一节。
