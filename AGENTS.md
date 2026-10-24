# 仓库协作约定

- 本仓库只收 Minecraft Java 版 1.20.1 活着的类人生物（生人）；类人亡灵不在职责范围内。身体模板来自 Human，权威身体状态和结算归 Framework，原版伤害接线归 Damage-Addon。
- Meridian 各附属在各自适用端按完整装配运行；不得添加缺少必需附属时的替代路径、降级或可选依赖判断。运行时必装不意味着让底层规则依赖具体 Fabric 宿主。
- `server/` 保存与 Minecraft 无关的生成、配置和区域库存规则；`fabric/` 负责实体认领、健康宿主、NBT、生命周期和环境服务接线。本仓库不提供客户端内容，不做粒子。
- 跨附属通信使用公开 Protobuf 和宿主接口；Human 内容使用公开内容 API，不依赖其内部 Fabric 实现类。身体快照、回执和释放请求遵循 Framework 与 Human 的协议。
- 已绑定身体的村民雷击时走原版和平分支，保留村民和身体，照常着火、受伤；无身体村民保持原版女巫转化。不要迁移雷击身体，也不要取消普通雷击伤害；村民转僵尸村民仍按死亡释放。
- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。代码按读取、校验、计算、请求、结果展开，命名、换行和注释密度与相邻代码保持一致。
- 纯规则的输入错误集中于 `server/.../HumanoidErrors.java`。随机生成由世界种子和实体 UUID 决定；重整代码时保留随机调用顺序与快照内容。
- Mixin 数组复制使用 `System.arraycopy`，或交给非 Mixin 工具类调用 `clone()`；数组方法引用和 Mixin 内直接调用数组 `clone()` 会导致当前 Mixin 转换失败。
- 仅保留必要的整体回归。Fabric GameTest 使用完整附属装配；测试运行依赖放入 GameTest classpath，正式 Fabric JAR 通过 `include` 嵌套 server JAR。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口；使用 patch 修改源码，保留已有未提交改动。交付执行 `test build`、Fabric GameTest、文档引用检查和 `git diff --check`。
- 未获授权不提交、推送或远端建仓。许可为 PolyForm Noncommercial 1.0.0，保留第三方许可。
