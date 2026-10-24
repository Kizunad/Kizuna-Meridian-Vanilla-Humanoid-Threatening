# 生人威胁批次实现计划

本仓库只收活着的类人生物（生人）。支持村民、流浪商人、掠夺者、卫道士、唤魔者、女巫和幻术师；类人亡灵不在范围内。每种生人生成游炁与真炁两档变体，身体都使用 Human Addon 的 `human:adult` 完整结构和 36 条经脉。

当前实现状态：阶段 1–3 的本仓库部分已经落地，阶段 4（真元属性战斗效果）仍待另行设计。

阶段 1 的纯 Java 生成器已经落地：

- 游炁层使用左右肺、大肠、胃、脾共 8 条经脉；真炁层使用左右心、小肠、肾、膀胱共 8 条经脉；其余 20 条保持未开拓。
- 游炁变体随机开启 1–8 条游炁层经脉，真炁变体完整开启游炁层并随机开启 0–8 条真炁层经脉。每条已开经脉的内腔容量增加 1625。
- 内腔和外壁按配置范围装填，未开拓经脉保留少量自然真元；所有库存先按实际密度换算体积，再通过 Framework 快照校验。
- 区域库存按属性合并相态，过滤 `meridian:none` 和目录外属性后调用 Qi-Variants 注册表。相同世界种子与实体 UUID 使用同一随机种子，结果可复现。
- 物种参数只改变开拓数、装填比例、外壁比例和手经/足经抽样权重；不会改变 Human 模板或属性目录。

配置资源为 `server/src/main/resources/meridian-humanoid.toml`。Fabric 阶段已完成以下接线：

- `HumanoidHealthHost` 只认领七种实体；首次健康查询从 Human `meridian_environment_host` 取得区域库存和世界时钟，生成失败时不创建临时身体。
- 身体快照、最多 32 条回执、区域来源和当前实体所有者写入实体 `meridian_humanoid` NBT。Framework 时间推进和健康回执中的 `released_qi` 会进入本模组世界 `PersistentState` 释放账本；所有者字段阻止其他实体借用快照，并兼容已有存档的身体 ID。
- 释放账本保存完整 `RegionRelease` 字节请求，服务器 tick 重试；Human 返回成功或 duplicate 后才删除记录。`UNLOADED_*` 与 `CHANGED_DIMENSION` 保留身体，`KILLED`/`DISCARDED` 结算并释放。
- 已绑定身体的村民（游炁和真炁）雷击时不转女巫，沿用原版和平难度分支的普通雷击处理：仍是同一个村民、保留原身体、照常着火，伤害经过 Damage-Addon 与健康宿主；无身体村民保留原版转化。注入只改变村民雷击入口的难度判断，不修改世界难度。
- 村民转僵尸村民后不再属于生人，原身体按死亡路径释放。OP 命令 `/meridian humanoid inspect` 输出档位、开拓经脉列表、主属性、密度、装填体积和区域来源。

本仓库的 Fabric 工程通过 Framework 的 `full-assembly.gradle` 使用完整装配：Humanoid Threatening Fabric JAR 进入 `modules`，Human/Damage/Healing 的 server JAR 进入 `serverLibraries`，Qi-Variants 的 shared/server JAR 进入 `localRuntime`。纯 Java 编译仍依赖相邻 Human Addon 已构建的 shared/server JAR；Human Fabric 与本仓库的源码联调存在循环依赖，源码联调只作为显式诊断选项使用。真元属性的战斗效果仍由 Vanilla-Damage 另批次接入。
