# 修为招敌

玩家修为涨了会招来生人。每名玩家有一个看不见的量叫风声，过线就排一场来犯：先有征兆，再来人。
出身附属里写了 `meridian_humanoid:crisis` 威胁的出身（仇家）不看风声，按自己的日程来。
设计和用户定下的取舍见会话仓 `handoff/i5-c4-c8-work/ORIGIN-AND-CRISIS-TASK.md` 的「乙、修为招敌」。

本页随实现分步补全；现在写到第 8 步：记录、配置、风声、在线计日、状态指令，出身的仇家、队伍表、启动核对，征兆，
来人现身，收场（赢、逃、输）、来人撤走、记仇的人，进度，功法谱的仇家来源。

## 天和记录

- 招敌里的「天」都是玩家自己的在线时间：在线满 24000 tick 算一天，不在线不走。
- 每名玩家一份记录 `CrisisRecord`，存在他唯一身体的附属进度里，键 `meridian_humanoid:crisis`；
  消息定义在 `server/src/main/proto/kizuna/meridian/humanoid/crisis/v1/crisis.proto`。
- 引擎经 Framework 的健康宿主读玩家身体、经活动宿主带修订号写回，不依赖哪个附属提供玩家身体。
- 进服以后身体读得到时才接上记录；记录平时在内存里改，每 1200 tick、出事时和下线时写回。
  下线写回在默认阶段，排在 Human 的下线清理之前。
- 记录解不开、版本不对或内容不合规是存档损坏：记一条错误，这名玩家本次在线不排招敌，也不拿空记录盖掉它。

## 风声

- 每 100 tick 读一次身体，和记录里上次读到的比：
  - 头一次突破到真炁，涨 `heat.rise.true_qi_breakthrough`；
  - 开脉头一次开到 `heat.rise.meridian_nodes` 里的某个条数，各涨 `heat.rise.meridian_node`；
  - 掉回去再开回来不重复涨。第一次读到身体只记下来，老玩家装上引擎时不算刚突破。
- 玩家杀了修炼的生人（按它实际的身体，凡人不算；来人不算，它们本就是冲玩家来的），
  凶手 `heat.rise.witness_radius` 格内另有活着的生人看得见他：涨 `heat.rise.witnessed_kill`。
- 玩家学会一卷稀有卷：涨 `heat.rise.rare_volume`。稀有卷是学卷表里至少有一处授它、并且每一处的概率都不超过
  `heat.rise.rare_chance` 的卷（默认表里是 `meridian_peddler:journey`、`meridian_wayfarer:bow_lower`、`meridian_door_breaker:lower`）；只在卷轴掉落里出现、表里没有的不算。
  底在记录接上时取，接上以前就会的不算；一次学了几卷，稀有的各涨一次。
- 最近一次涨以后满 `heat.decay.after_days` 天才开始落，之后每满一天落 `heat.decay.per_day`，落到 0 为止。
- 排一场要同时满足：总开关开着；没有排着的一场；风声到 `heat.threshold`；
  过了开局保护期 `frequency.protection_days`；打过的话离上一场了结也过了 `frequency.min_interval_days`；
  游炁玩家开脉到 `frequency.wandering_ready_meridians`（默认 8，八条游炁正经开齐才能突破）。
- 风声招来的一场用 `generic_party` 那张队伍表，排上以后过 `omen.days` 天来人。
  征兆给 `omen.count` 次（按世界种子、玩家和第几场取），均匀铺在这几天里。

## 出身的仇家

出身附属（`meridian_origin`）是硬依赖。本仓经入口 `meridian_origin` 登记威胁类型 `meridian_humanoid:crisis`，
出身 JSON 的 `threats` 里每写一条就是这个出身的一条仇家：

```json
{
  "type": "meridian_humanoid:crisis",
  "params": {
    "party": "meridian_humanoid:feud",
    "first_after_days": [8, 14],
    "interval_days": [12, 20],
    "stages": 3,
    "min_realm": "wandering_qi",
    "foe_volume": "meridian_door_breaker:lower"
  }
}
```

- 各字段的含义照会话仓任务单「第 2 段接口草稿」第六条：`party` 队伍表；`first_after_days` 第一拨最早、最晚几天来；
  `interval_days` 两拨之间；`stages` 一共几拨，0 是没有了断；`min_realm` 可省，默认 `wandering_qi`；
  `foe_volume` 可省，最后一拨（`stages` 为 0 时每一拨）头目必会、必掉的卷。
- 解析器只管形状和范围：ID 带命名空间，天数 0 ≤ 最早 ≤ 最晚，`stages` 不为负，`min_realm` 只能是游炁或真炁。
  不对的话出身附属读数据包时失败，报错里带出身 ID。
- 一条仇家的键是「出身 ID#下标」，下标是它在这个出身的 crisis 条目里的位置；进度存在招敌记录的 `feuds` 里。
- 引擎怎么知道玩家的仇家：
  - 玩家选定出身（`Origins.CHOSEN`，调试指令定出身也算）时记下，马上补进记录、开始计日并写回；
  - 每次进服出身附属读完身体（`Origins.READY`）时再记一次，老玩家记录里没有的仇家这时补上。
- 计日：
  - 玩家到了 `min_realm` 才开始，第一拨在 `first_after_days` 里按世界种子、玩家和仇家取整天；
  - 离到期日还有 `omen.days` 天时排上那一场，来人的时刻就是到期日；被别的一场耽搁过的，从排上起留足征兆期。
- 和通用招敌共用「同时只排一场」、开局保护期和最短间隔。几条同时到了按出身里写的先后；仇家先于风声。
- 记录里有、出身里已经没有的仇家（数据包改过）留着不动，只是不再排。

## 首批出身

本仓的出身在 `fabric/src/main/resources/data/meridian_humanoid/meridian_origin/origin/`，其中四个出身带仇家队伍表，
出身卡和征兆的文字在本仓语言文件的 `origin.meridian_humanoid.*`、`crisis.meridian_humanoid.<队伍表>.*` 下。
玩家生来就在游炁，所以 `min_realm` 都用默认值：一选定就开始计日。

| 出身 | 天赋 | 仇家（队伍表、第一拨、两拨之间、拨数、必掉卷） |
|---|---|---|
| `farmer` 寻常农户 | 村民声望 +20 | 没有 |
| `orphan` 灭门遗孤 | 开局会《行矢·百步》（`meridian_wayfarer:bow_upper`） | `clan_foes`，8–14 天，12–20 天，3 拨，`meridian_wayfarer:bow_lower` |
| `apprentice` 药铺学徒 | 开局会《炼药卷·熬药》（`meridian_brewing:brewing_basics`）；开局会聚气汤、苦黄膏两张药方 | `recipe_thief`，6–10 天，12–20 天，1 拨 |
| `disgraced` 罪臣之后 | 开局会《破门斩·木门》（`meridian_door_breaker:upper`） | `captors`，10–16 天，24–36 天，没有了断，`meridian_door_breaker:lower` |
| `merchant` 行商之后 | 参考负重 ×1.25 | `highwaymen`，5–9 天，10–14 天，没有了断 |
| `reborn` 重生归来 | 开局会后世吐纳卷，拥有基础吐纳授权 | 旧簿仇家终身来犯，三档阵容各按胜场增援 |

- 队伍表：`clan_foes` 掠夺者为主，三档各分 0/1/2 拨，一拨比一拨强，第 2 拨头目是真炁精锐；`recipe_thief` 头目是真炁女巫，
  `wandering` 档有时一个人来、有时带一个随从，往上的档随从多些，`true_many` 档她是精锐；`captors` 只有卫道士；
  `highwaymen` 人少、多是游炁。
- 药方天赋由炼药附属登记（`meridian_brewing:starting_recipes`），负重天赋由负重附属登记（`meridian_burden:carry_capacity`），
  所以本仓硬依赖这两个附属：缺了它们出身数据读不进来。
- 文字由 Gemini 照会话仓 `handoff/origin-lore-20261115/3b/` 的资料写，逐句对过代码；改文字改资料重写，不手改语言文件。

## 开局卷、基础吐纳与村民声望

本仓经同一个入口登记以下出身天赋（`fabric/.../OriginTalents.java`）。

- `meridian_humanoid:starting_volume`，一次性，参数 `{"volume": "<卷 ID>"}`：选定出身时经功法服务学会这一卷，已经会了不变。
  身体宿主不在、前置卷没会时这次不算发成，出身附属下次进服再发。一个出身写几条就按写的先后学。
  这一类放在本仓不放功法库：出身附属要 Human 或本仓的身体宿主才能初始化，功法库自己的测试装配里没有。
- `meridian_humanoid:villager_standing`，常驻，参数 `{"reputation": <-100..100>}`：加在原版 `Villager.getPlayerReputation` 的返回值上
  （`VillagerOriginStandingMixin`），原版打折和铁傀儡认人都读这个数。几条相加；没选出身是 0。
- 开局卷学会时照常走功法库的「会的卷变了」，但这几卷在学卷表里都是必授的，不算稀有卷，不涨风声。
- `meridian_humanoid:basic_breathing` 是无参数的常驻天赋。选定、进服和出身数据重载时，按当前出身重新确定基础吐纳授权。
  已学的卷由功法库保存，书籍阅读进度仍由玩家实际阅读取得。

## 队伍表

数据包内容：`data/<命名空间>/meridian_humanoid/crisis_party/<名字>.json`，ID 是「命名空间:名字」。
本仓自带通用招敌用的 `meridian_humanoid:covetous`。

```json
{
  "lineups": [
    {"band": "wandering", "leader": {"species": "minecraft:vindicator", "tier": "true_qi"}},
    {"band": "true_many", "stage": 1, "weight": 2,
     "leader": {"species": "minecraft:evoker", "tier": "true_qi", "elite": true},
     "followers": [{"species": "minecraft:vindicator", "tier": "true_qi", "count": [2, 3]}]}
  ]
}
```

- `band` 是玩家所在的档：`wandering`（游炁，含还没开脉的）、`true_few`（真炁，开脉不到 `strength.true_many_meridians`）、
  `true_many`。
- `stage` 可省，默认 0：打赢几拨以后起用这种阵容。`weight` 可省，默认 1。
- `leader` 是头目，打死他就算赢；`elite` 可省，只有真炁头目能是精锐。`followers` 可省，`count` 是 [最少, 最多]。
- 物种只能是灾民一方：掠夺者、卫道士、唤魔者、女巫。混进村民、行商会被阵营规则自己打起来。`tier` 只能是游炁或真炁。
- 三档每一档都要有一种 `stage` 为 0 的阵容；一种阵容最多来的人数（头目加随从上限）不超过 `strength.max_party`。
- 挑阵容：按记录里最近一次看到的身体分档；这一档里取 `stage` 不超过当前拨次的最大值，同一拨次写了几种按 `weight` 抽。
  每种随从的个数在 `count` 里均匀取。这些都从世界种子、玩家、已了结几场和来人时刻混出的种子来，同一场重试结果不变。

## 征兆

排上的一场先过征兆期，`omen_at` 记着每次征兆的时刻（在线时间），`omens_given` 记给到第几次。
到了一次的时刻，玩家活着、不是旁观，才看给什么：

- 一场只留一张字条。最后一次还没留过，或者身边没有村民，就留字条。
- 身边有村民（`omen.rumor_radius` 格内醒着、看得见玩家的村民，挑最近的）就由它说一句传言：村民转头看着玩家，
  第几次征兆挑第几句（两句轮着）。
- 都给不了（字条留过、身边又没有村民）就等；等到这一次过完（下一次的时刻，最后一次是来人时刻）还没碰上就跳过。
- 给过、跳过都记一次；留了字条另记 `note_given`。来人时刻一到，没给完的不再补。

传言和下面记仇的人那句话，Threatening 只发事件 `HumanoidSpeech.SPOKE`（说话的生人、对着说的玩家、那一句的翻译文本），
不依赖谁来听；Taunts 附属听了去发浅灰色聊天和头顶气泡。

字条是原版纸，物品名 `item.meridian_humanoid.crisis_note`，正文三行，灰色正体：

- 重生点是床、和玩家在同一维度、`omen.note_bed_radius` 格内、区块已加载，就放在床上；
- 不然放在玩家身后（背对的方向左右至多偏 60 度，先 5 格再 4、6 格）地上站得住、头顶两格空、和脚下高差 3 格以内的地方；
- 都找不到这一回不放，下一步再试。掉在地上不消失。

文字都由队伍表 ID 推翻译键（`crisis.<命名空间>.<名字>.` 后接，名字里的斜杠换成点）：

| 键 | 用处 |
|---|---|
| `omen.rumor.1`、`omen.rumor.2` | 传言两句 |
| `omen.note.1`–`omen.note.3` | 字条正文三行 |
| `leader_name` | 头目头顶的名号 |
| `leader_return` | 记仇的人带队回来、照面时那一句（按它原先那张队伍表） |

通用队伍表 `meridian_humanoid:covetous` 的文字由 Gemini 照事实文件写成，逐句对过代码，放在会话仓
`handoff/crisis-lore-20261115/`（`gen.py`、`facts-*.md`、生成稿），中英两份进语言文件。

## 来人

- 到了来人时刻还没来人，玩家在主世界、黄昏到天亮前（一天里第 12000–23000 tick）、不是创造或旁观，才找落脚点；
  不合适就等，找不到落脚点隔 `arrival.retry_seconds` 秒再试。
- 落脚点：离玩家水平 `arrival.min_distance`–`max_distance` 格的环上随机找，区块已加载、脚下是实心面、站的一格和头顶
  一格是空的、不在水里、和玩家高差不超过 6 格，并且玩家看不见——在玩家身后半边，或者玩家眼睛到来人头顶之间有方块挡着。
- 头目站在落脚点，随从挨着散开。都按写的档位出生，走原版事件出生（不当巡逻队、不随机扛旗），常驻不消失，
  打上实体标签 `meridian_humanoid.crisis_raider`，追击距离至少是现身距离上限再加 16 格。
- 精锐头目：真炁，游炁、真炁两层十六条经脉全开，开着的经脉内腔和外壁装填、密度都取本档上限；
  会本物种真炁档学卷表里写着的每一卷（稀有卷在内），概率不看。
- 出身仇家的最后一拨（`stages` 为 0 时每一拨）的头目再会 `foe_volume`，连同它的前置卷。通用招敌不带。
- 头目的名字是翻译键 `crisis.<命名空间>.<名字>.leader_name`（名字里的斜杠换成点），文字在语言文件里。
- 来人以后记录里记下来的时刻、头目和全部来人；玩家这一刻算被看见。
- 来人只盯这名玩家：大脑选敌时只给这名玩家（不在线、死了、不在同一世界、创造或旁观时这一刻没有对手）；
  原版改目标也只接受这名玩家或清空。被魅惑时听魅惑的。
- 记仇的人带队的一场见下面「记仇的人」。

## 收场

来了人以后每 20 tick 看一次，三种结局各了结这一场：

- 赢：头目死了（怎么死的都算）。在它倒下的地方掉一卷：出身仇家最后一拨（`stages` 为 0 时每一拨）掉 `foe_volume`；
  不然从头目会的、有卷轴的稀有卷里按这一场的种子挑一卷，头目一卷稀有卷也不会就不掉。记仇的人带队的，
  它身上带着的东西也一起掉。风声留 `heat.kept_after_win`，这份仇了断（记仇的人清掉）。
- 逃：离所有来人至少 `outcome.escape_distance` 格、并且 `outcome.escape_unseen_seconds` 秒没被来人看见
  （来人在逃脱距离内看得见玩家就重新计时）；或者来了以后撑过 `outcome.escape_time_limit_days` 天。
  玩家下线也算逃。来了人、名下却一个来人都不剩（崩服后重进），出身对过以后同样按逃了结。风声留 `heat.kept_after_escape`。
- 输：死在盯他的来人手里（恼鬼、尖牙算它们的主人，最后打他的那一个也算）。在掉落之前了结。
  代价按 `outcome.loss_penalty`：`light` 只有平常死一次的代价；`medium`（默认）另被头目从身上拿走一件——先挑一卷没读的卷轴，
  没有就挑一瓶带效果的原版药水（喝的、喷溅、滞留，白水瓶不算），从那一摞里拿一个，都没有就不拿；
  `heavy`（再伤一条经脉）暂时不能用，写了启动失败：经脉伤了还没有恢复的规矩，伤了就再也好不了。
  哪一档都不跌境、不删进度和已学功法。风声和逃了一样留 `heat.kept_after_escape`。
- 出身仇家的一拨：赢了拨次 +1，打满 `stages` 就了断，不然下一拨在 `interval_days` 里按种子取一天；
  逃了、输了拨次不动。若配置了 `setback_days`，在这个区间取下一拨日期；省略时沿用 `interval_days` 的下限。
- 了结以后场数 +1、记下了结时刻，排着的那一场清掉，马上写回。

### 撤走

- 一场了结后还活着的来人都转成撤走：不再盯人，大脑不给对手，原版改目标只接受清空；
  每 20 tick 朝背离 48 格内最近的玩家的方向走一段。
- 撤够 3 秒、48 格内没有玩家（旁观的不算）看得见它，就消失。卸载了的不再管，以后加载进来按孤儿清。
- 玩家下线时名下盯人的、撤走的来人当场清走。
- 打了 `meridian_humanoid.crisis_raider` 标签、却既不在盯人也不在撤的（崩服、停服前没收拾掉的），一加载进来就清走。

### 记仇的人

- 逃了、输了，这一场的头目记仇：记下它的物种、档位、会的卷、哪张队伍表、哪条仇家、是哪一只，碰过几回（每回 +1）。
  输了被拿走的东西记在它身上（原版物品存档的 NBT），输几回拿几件，攒着。
- 它撤走途中被追上打死：身上的东西掉回来，这份仇了断（没有另外的掉落）。
- 只要还记着仇，下一场（风声招来的、仇家的都一样）由它带队：物种照它；档位取它记着的和这一场阵容头目里高的那个；
  会它记着的卷，再多会一卷——本物种这一档学卷表里头一卷它还不会的。这一场阵容的头目是精锐，它也是精锐；
  没得多会、又已是真炁，也成精锐。随从照这一场的阵容。名字照它原先那张队伍表。
- 它带队来的那一场，它走到玩家 12 格内、看得见玩家时先说一句 `leader_return`，一只只说一次。
- 它带队的一场又逃了、输了，还是它记仇，碰过的回数、会的卷跟着更新；赢了这份仇了断。记录里同时只有一个记仇的人。

## 进度

四条挂在「道上的人」（`meridian_progression:cultivation/cultivators`）下，文件在
`fabric/src/main/resources/data/meridian_humanoid_host/advancement/cultivators/`，共用一个触发器
`meridian_humanoid_host:crisis`（`CrisisTrigger`），条件写 `"event"`：

| 进度 | 文件 | `event` | 什么时候给 |
|---|---|---|---|
| 风声 | `omen.json` | `omen` | 传言说出口或字条放下去的那一刻（到时刻给的、`omen` 指令给的都算） |
| 不速之客 | `repelled.json`（父：风声） | `repelled` | 赢了一场（头目死了，怎么死的都算）；逃、输不给 |
| 冤家路窄 | `grudge_slain.json`（父：不速之客，goal） | `grudge_slain` | 记仇的人带队的那一场赢了，或它撤走途中被追上打死 |
| 了断 | `feud_ended.json`（父：不速之客，challenge） | `feud_ended` | 赢了一场，出身的那条仇家这一回从没打完变成打完（`CrisisFeuds.ended`） |

说明文字由 Gemini 照事实写（会话仓 `handoff/crisis-lore-20261115/gen-advancement.py`、`facts-advancement.md`），
标题照设计表，英文标题也由它译。「出身仇家打完」只看这一回了结前后：已经打完的仇家不会再排，所以只给一次。

## 功法谱

出身仇家必掉的那一卷登记成功法谱的来源暗示（功法库 `TechniqueVolumeSources`，登记名 `meridian_humanoid_host:crisis_foes`）：

- 每个出身里写了 `foe_volume` 的仇家登记一条「仇家来源」，带出身显示名的语言键（`Origins.nameKey`），
  功法谱照它拼「某出身的仇家身上有」那句；没写 `foe_volume` 的仇家不登记，同一出身同一卷只登记一次。
- 只用来拼展示句子，不管掉不掉、学不学。出身附属本身不依赖功法库，由这边读出身的威胁参数去登记。
- 每次汇总都读出身附属当前那份数据包；`/reload` 收下新的队伍表后让在线玩家换一份。
- 功法库开服建好服务时、玩家进服时都会逐条核对来源（卷要在功法目录里、出身语言键不能空），写错了开服就失败。

## 启动核对

- 开服时读队伍表，有一份不对就启动失败，报错里带文件 ID。
- 第一个服务器 tick 核对一次（那时功法注册表和出身才齐），不对直接失败：
  - `generic_party` 指向的队伍表在；
  - 每张队伍表的人数不超过 `strength.max_party`；
  - 每个出身每条 crisis 的 `party` 在，写了 `foe_volume` 的那一卷已装、有卷轴（赢了要掉）；
  - 每个出身的开局卷已装，前置卷写在同一出身更前面的开局卷里（`OriginVolumes`，和学卷表一起核对）。
- `/reload` 成功后重读并核对；新的不对就留着旧的队伍表，记一条错误。开局卷也再核对一次，不对只记错误（出身已经换成新的了）。这一步排在重载事件默认阶段之后，
  出身附属先重读完出身再核对。

## 配置

`config/meridian-humanoid-crisis.toml` 第一次启动时从 JAR 导出，之后只读，程序不改它。

- 缺新键按 JAR 默认值运行，启动时日志列出缺了哪些键。
- 默认文件里没有的键、把标量写在默认是表的位置上、值超出范围，都拒绝启动，错误信息带完整键路径。
- 各键的含义写在默认文件的注释里（`server/src/main/resources/meridian-humanoid-crisis.toml`）。

## 指令

挂在 `/meridian humanoid crisis` 下，权限 2，全部能 Tab 补全：

- `status [targets]`：在线天数、风声和过线值、了结过几场、排着的那一场（队伍表、第几拨、征兆进度、来人时刻）、
  出身的仇家、记仇的人（身上带着夺走的几件）。还没接上记录的玩家说明原因。
- `strike [targets [party]]`：不看时辰立刻来人。排着没来的那一场马上来，还没给的征兆不再补；没排的按 `party`（缺省 `generic_party`）
  排第 0 拨、不给征兆、马上来。已经来了人、排着另一张队伍表、没有这张队伍表、找不到落脚点都失败并说明原因。
  `party` 补全当前收下的队伍表，只能跟在目标后面（省略目标时后面那个词按目标省略解析）；给自己指定队伍表写 `@s`。
- `omen [targets [party]]`：不等时刻，立刻给下一次征兆，给什么照上面的规矩。没排着就先照风声招来的那样排一场
  （按 `party`，缺省 `generic_party`；第 0 拨、照常定征兆次数和来人时刻），给成了才记下。没接上记录、已经来了人、
  排着另一张队伍表、没有这张队伍表、征兆给完、字条留过身边又没有村民、字条没处放都失败并说明原因。
  `party` 和 `strike` 一样补全当前收下的队伍表、只能跟在目标后面；用来看某张队伍表（比如出身的仇家）的传言和字条。

## 测试

- 单测（`server`）：`CrisisRulesTest`、`CrisisRecordsTest`、`CrisisHeatTest`、`CrisisScheduleTest`、`CrisisFeudsTest`、
  `CrisisPartyTest`、`CrisisArrivalTest`（时辰、挑阵容、随从个数、仇家的卷、来人的记录、调试叫人时没到点的征兆时刻压到来人那一刻）、
  `CrisisOutcomeTest`（三种结局的风声、仇家拨次、记仇和攒东西、逃脱条件、掉哪一卷、记仇的人回来变强、稀有卷）、
  `OriginVolumesTest`（开局卷要装着、前置卷写在前面），
  以及 `HumanoidBodyGeneratorTest` 里的精锐出生。
- GameTest：`HumanoidCrisisGameTest`（记录、风声、状态指令）、`HumanoidCrisisFeudGameTest`（出身的仇家：选定后计日、
  老玩家重进补上、到期排上、启动核对、功法谱的仇家来源已登记且去重）、`HumanoidCrisisRaidGameTest`（叫来一拨精锐：人数、档位、精锐、学卷、名字、
  距离、标签、只盯这名玩家；仇家最后一拨头目带卷）、`HumanoidCrisisOutcomeGameTest`（打死最后一拨头目掉卷、仇了断；
  头目死后随从撤走、没人看见就消失；逃了记仇、再来变强；输了被夺卷轴、追上打死掉回来；记仇的人带队、掉回药水；
  下线算逃并清走来人；名下没人按逃了结、孤儿清走；当着生人杀人和学稀有卷涨风声）、`HumanoidCrisisOmenGameTest`
  （没有村民只留字条、在玩家身后、不消失；村民说传言、最后一次必留字条；调试指令把字条留在近处的床上；
  记仇的人照面说一句、只说一次；说的话从 `HumanoidSpeech.SPOKE` 听）、`HumanoidOriginTalentGameTest`（首批五个出身和四张队伍表读进来、
  出身卡每一行都有文字；遗孤的仇家一拨比一拨强；罪臣之后选定就会木门卷、再定一次不出错；寻常农户的村民声望多 20、行商之后不加；
  参数写错的开局卷和声望读不进来）。
  四条进度的断言放在这两类里：征兆三条用例看「风声」给的时刻；收场五条用例在头目死前、死后看三条结局进度
  （`CrisisGameTests.endings`：正好哪几条完成，别的都没完成）。
  要看「有没有人看见」的用例各自送到远处一道（`CrisisGameTests.standApart` 的 `lane`），彼此隔 512 格。测试数据包里有测试出身 `meridian_humanoid_test:orphan`（两条仇家）和
  测试队伍表 `meridian_humanoid_test:feud`。测试玩家走原版进服入口并声明 Human 要求的界面通道（`CrisisTestPlayer`），
  否则 Human 会暂停身体、引擎读不到。

重生归来的旧簿仇家首拨定在 6–10 在线日，三种收场都隔 12–16 日，胜利累计到两拨、四拨时各档增援，四拨后沿用该档最强阵容。生人负责往后每一拨；Story 的旧案结局只负责证物和尾信。
