# 仓库协作约定

- 本仓库只收 Minecraft Java 版 26.2 活着的类人生物（生人）；类人亡灵不在职责范围内。身体模板来自 Human，权威身体状态和结算归 Framework，原版伤害接线归 Damage-Addon。
- Meridian 各附属在各自适用端按完整装配运行；不得添加缺少必需附属时的替代路径、降级或可选依赖判断。运行时必装不意味着让底层规则依赖具体 Fabric 宿主。
- `server/` 保存与 Minecraft 无关的生成、配置和区域库存规则；`fabric/` 负责实体认领、健康宿主、NBT、生命周期和环境服务接线。本仓库不提供客户端内容，不做粒子。
- 跨附属通信使用公开 Protobuf 和宿主接口；Human 内容使用公开内容 API，不依赖其内部 Fabric 实现类。身体快照、回执和释放请求遵循 Framework 与 Human 的协议。
- 已绑定身体的村民雷击时走原版和平分支，保留村民和身体，照常着火、受伤；无身体村民保持原版女巫转化。不要迁移雷击身体，也不要取消普通雷击伤害；村民转僵尸村民仍按死亡释放。
- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。代码按读取、校验、计算、请求、结果展开，命名、换行和注释密度与相邻代码保持一致。
- 纯规则的输入错误类型集中于 `server/.../HumanoidErrors.java`，错误文案写在抛出的地方，不另建文案常量。随机生成由世界种子和实体 UUID 决定；重整代码时保留随机调用顺序与快照内容。
- Mixin 数组复制使用 `System.arraycopy`，或交给非 Mixin 工具类调用 `clone()`；数组方法引用和 Mixin 内直接调用数组 `clone()` 会导致当前 Mixin 转换失败。
- 仅保留必要的整体回归。Fabric GameTest 使用完整附属装配；测试运行依赖放入 GameTest classpath，正式 Fabric JAR 通过 `include` 嵌套 server JAR。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口；使用 patch 修改源码，保留已有未提交改动。交付执行 Fabric GameTest、文档引用检查和 `git diff --check`。
- 未获授权不提交、推送或远端建仓。许可为 PolyForm Noncommercial 1.0.0，保留第三方许可。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。

## 速查

### 自动生成

- Fabric 模组 `meridian_humanoid_host`，清单 `fabric/src/main/resources/fabric.mod.json`
  - `main` → `dev.kizuna.meridian.humanoid.fabric.HumanoidFabric`
  - `meridian_activity_host` → `dev.kizuna.meridian.humanoid.fabric.HumanoidActivityHost`
  - `meridian_brewing_charm_controller` → `dev.kizuna.meridian.humanoid.fabric.HumanoidCharmController`
  - `meridian_health_host` → `dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost`
  - `meridian_origin` → `dev.kizuna.meridian.humanoid.fabric.CrisisOrigins`、`dev.kizuna.meridian.humanoid.fabric.OriginTalents`
  - `meridian_player_control_driver` → `dev.kizuna.meridian.humanoid.fabric.HumanoidPlayerDriver`
  - `meridian_technique_host` → `dev.kizuna.meridian.humanoid.fabric.HumanoidTechniqueHost`
- 数据目录：`fabric/src/gametest/resources/data/meridian/`、`fabric/src/gametest/resources/data/meridian_humanoid_test/`、`fabric/src/main/resources/data/meridian_humanoid/`、`fabric/src/main/resources/data/meridian_humanoid_host/`、`fabric/src/main/resources/data/minecraft/`
- 资源目录：`fabric/src/main/resources/assets/meridian_humanoid/`、`fabric/src/main/resources/assets/meridian_humanoid_host/`
- Protobuf 数据文件（含功法目录）：无
- 随包 TOML：`server/src/main/resources/meridian-humanoid-brain.toml`、`server/src/main/resources/meridian-humanoid-crisis.toml`、`server/src/main/resources/meridian-humanoid.toml`、`server/src/main/resources/meridian-witch-ai.toml`
- 测试源集：`fabric/src/clienttest/`、`fabric/src/gametest/`、`fabric/src/test/`、`server/src/test/`
- `kz` 装配 ID：`humanoid-threatening`（`--with` 使用工作树路径）
- `kz test --part unit`：`test build`
- `kz test --part gametest`
  - `:fabric:runGametest -PgametestRunDir=build/{name}`，运行目录 `fabric/build/{name}`
- `kz client` 项目：无

### 常见坑

- 2026-11-20 固定打法的夹具要显式约束出生档与外卷池。随机学到的外卷会改变选式和走位。来源 `POOL-TEST-ISOLATION-TASK.md`。
- 2026-11-21 包含档位或出生功法断言的 GameTest 固定种子、显式指定档位，并在起手前断言已学卷。来源 `FLAKES-20261121-TASK.md`。
- 2026-11-10 改随包 `meridian-humanoid-brain.toml` 后用 `kz test <树> --part gametest` 启动新目录，旧文件不会被默认值覆盖。来源 `C5b-FIX-TASK.md`。
- 2026-11-26 身体按需创建。读身份、库存或目击者资格时走只读查询，首次观察时的 `inspectBody` 会改变出生流程。来源 `MOB-PURSE-TASK.md`。
- 2026-11-26 测试产出的日志放在运行目录或模块 `build/` 下，检查时也留意已跟踪的空日志。来源 `TEST-LOGS-OUT-TASK.md`。
