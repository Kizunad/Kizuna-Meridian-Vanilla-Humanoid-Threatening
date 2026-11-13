# 魅惑控制、动作速度与反胃

本仓实现 Brewing 定义的 `meridian_brewing_charm_controller`。Brewing 持有药效和醒的规则，
本仓只执行已授权会话；玩家会话通过 Human 的 `meridian_player_control_host` 显式映射协议。
主线程与 caller 校验、4096 字节限长、稳定 UUID、UPDATE 不延长期限和 END 重放均在入口检查。
结束墓碑保留到会话期限，已过期 START 只确认、不重新认领。START 重试不回滚已经缩短的期限或更新的目标。

修炼者仍使用现有大脑感知、战术与功法选择，当前对手改为 Brewing 给出的目标。
开始、换目标和结束时撤旧倒排计划与施放，保留已装入的 Goal，避免重复安装。
无对手时由原版导航保持两格跟随；凡人直接使用原版目标与攻击 Goal。
原版选敌不能覆盖控制目标；近战、射击与功法筛选保护施药者、宠物和同队玩家。
已发出的普通投射物命中也经伤害事件保护当前施药者一方。

本仓同时实现 Human 定义的 `meridian_player_control_driver`，接收可信身体、已学功法、持物、
服务端位置/视角/周围实体与会话。请求最多 4 MiB，会话和服务端 tick 必须匹配当前授权；
序号原样回显，由 Human 核对是否对应这一次请求。
选式复用 Technique 的绑定、支付检查、持物规则和 AI 选择器，排除修炼类；已经施放时继续推进当前式。
手持物保持原样，不换装备，不使用箱子或主动吃喝。输出移动、看向、跳、疾跑、攻击与式引用，TTL 为 4 tick。
实际攻击和施放由 Human 执行，费用属于玩家身体。

玩家路径只使用 `Kizuna-Pathfinding-Framework` 的纯 Java `core`，由本仓适配 Minecraft 26.2 只读碰撞网格。
搜索限于水平 20 格、垂直 4 格和 400 次预算；避开流体与危险方块，不加载新区块。
普通走路、一级跳与最多两格安全下落经原版输入执行；短路径段缓存 10 tick，起跳后沿同一落点持续输入。
移动相对当前视角换算，不将攻击朝向转向路径拐点。没有将功法跃步当成免费移动。

构建前须在寻路仓执行 `flock "/tmp/kizuna-gradle.lock" "./gradlew" --no-daemon :core:test :core:build`。
本仓通过本地 flatDir 读取其现有 `core/build/libs/kizuna-pathfinding-framework-core-0.1.0-SNAPSHOT.jar`，
并嵌入 Fabric JAR；没有发布新的 Maven 坐标，也不装寻路仓面向 1.20.1 的 Fabric 适配器。
Brewing 的 fabric/server/shared 使用源码组合，完整运行装配仍由 Framework 的统一清单读取。

`CHARM` 僵直取消主动 AI 移动、导航、跳、攻击和物品使用；保留重力与击退。
`HIT` 沿既有减速与起式拒绝，不能启用魅惑全锁。女巫自喝绕过原版 `startUsingItem`，
因此另在 `Witch.aiStep` 中止其喝药分支、清临时药水和饮药减速，再推进父类 AI。
反胃期限与普通吃喝共用 Brewing 的真实身体规则。

`HumanoidTempo` 调 Human 的公共 `HumanLimbTempo`，按实时曲线投影移动属性和主手臂倍率；
移动倍率经 Technique 的 `TechniqueMovementSpeed` 按 √倍率 加到移速属性，实际移速是倍率倍。
同宿主内直接读取 Store 已提交的不可变快照，无能力修正时跳过肌肉遍历并撤回已有速度投影。
原版近战 Goal 重置冷却、远程 Goal 的距离插值间隔和弩的新等待期各只缩放一次；技法时长仍由 Technique 处理。
inspect 保留 Human 的完整器官/组织定义与状态，包括两肾、脑、注意组织和能力修正，Brewing 直接按同一规则算档。

`HumanoidCharmGameTest` 登记到源资源和 `persistence-tests.gradle` 的完整清单，覆盖真实喷溅后的
修炼者用式与跟随、凡人原版攻击、魅惑/受击门禁、实际近战冷却、女巫自喝、inspect 和会话重试。
玩家及阵营联调由 Brewing GameTest 覆盖；真实客户端移动、转头与多人性能未测。
