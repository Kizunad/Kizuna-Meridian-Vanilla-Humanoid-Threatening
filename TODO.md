# TODO

## 范围

本仓库只收 Minecraft Java 版 1.20.1 的类人生物。非类人生物另起仓库，不在此处登记。

## 当前批次进度

- [x] 七种类人生物（村民、流浪商人、掠夺者、卫道士、唤魔者、女巫、幻术师）的游炁/真炁生成器、区域抽取和配置校验。
- [x] Fabric 健康宿主、实体 NBT、按需创建、Human 世界时钟和区域服务接线。
- [x] 死亡/自然消失释放账本、区块卸载和跨维度保留、村民转女巫身体迁移。
- [x] 真炁粒子和 `/meridian humanoid inspect` 调试命令。
- [ ] 真元属性的战斗效果（另批次设计）。

## 类人生物清单

- [x] 村民 — `minecraft:villager`
- [x] 流浪商人 — `minecraft:wandering_trader`
- [x] 掠夺者 — `minecraft:pillager`
- [x] 卫道士 — `minecraft:vindicator`
- [x] 唤魔者 — `minecraft:evoker`
- [x] 女巫 — `minecraft:witch`
- [x] 幻术师 — `minecraft:illusioner`

## 完成标准

每个类人生物的清单项完成时，应具备符合物种特点的身体模板、部位/组织/器官定义、经脉拓扑、基础状态与致死规则；接入对应原版实体 ID 和伤害处理；并有针对定义注册及伤害/死亡行为的验证。

## 待定问题

- [ ] 僵尸村民等类人亡灵是否纳入本仓库，等待确认后再添加。
- [ ] 后续新增类人生物的批次、模板和物种偏向。
