# Kizuna's Meridian Vanilla Humanoid Threatening

本仓库只为 Minecraft Java 版 1.20.1 原版活着的类人生物（生人）提供 Meridian 经脉身体内容。类人亡灵不属于本仓库范围。

支持村民、流浪商人、掠夺者、卫道士、唤魔者、女巫和幻术师的游炁/真炁变体。纯 Java 生成器和 Fabric 宿主已经实现：身体按需创建并写入实体 NBT，时间和区域库存来自 Human 环境服务，死亡/自然消失通过世界释放账本回写区域；跨维度和区块卸载保留身体。已有身体的村民雷击时保留村民和原身体，照常着火、受伤；无身体村民保持原版女巫转化。OP 可用 `/meridian humanoid inspect <target>` 检查快照。七种生人按学卷表学功法，由类人大脑判断撤退或对敌并施放；规则与配置 `meridian-humanoid-brain.toml` 见计划文档「类人大脑」一节。

构建需要 Java 17，并通过 Gradle composite build 使用相邻的 Framework、Human Addon 和 Qi-Variants Addon。Fabric 开发装配复用 Framework 的 `full-assembly.gradle`：Humanoid Threatening Fabric JAR 进入 `modules`，Human/Damage/Healing 的 server JAR 进入 `serverLibraries`，Qi-Variants 的 shared/server JAR 进入 `localRuntime`：

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

`fabric/build.gradle` 的 `clearNestedJarCache` 在每次 `remapJar` 前删除 `fabric/build/loom-cache/temp/modprocessing`：Loom 只按坐标复用这里的嵌套 JAR，SNAPSHOT 坐标不变时会把旧 server JAR 打进发布 JAR。

实现范围和当前 7 种生人清单见 [TODO.md](TODO.md)。计划文档位于 [docs/wiki/Implementation-Plan.md](docs/wiki/Implementation-Plan.md)；其他模组登记自己的类人物种见 [docs/wiki/Species-Registration.md](docs/wiki/Species-Registration.md)。Fabric 构建需要先生成相邻 Human Addon 的 shared/server JAR；源码联调参数仍为 `-PmeridianUseSourceHuman=true`，但 Human Fabric 与本仓库存在完整装配循环时应使用已构建产物。
