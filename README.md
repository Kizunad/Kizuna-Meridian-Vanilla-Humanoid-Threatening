# Kizuna's Meridian Vanilla Creature Threatening

本仓库为 Minecraft Java 版 1.20.1 原版生物提供 Meridian 经脉身体内容。

当前批次是村民、流浪商人、掠夺者、卫道士、唤魔者、女巫和幻术师的游炁/真炁变体。纯 Java 生成器和 Fabric 宿主已经实现：身体按需创建并写入实体 NBT，时间和区域库存来自 Human 环境服务，死亡/自然消失通过世界释放账本回写区域；跨维度和区块卸载保留身体，村民转女巫迁移同一身体。真炁实体使用主属性颜色粒子，OP 可用 `/meridian creature inspect <target>` 检查快照。

构建需要 Java 17，并通过 Gradle composite build 使用相邻的 Framework、Human Addon 和 Qi-Variants Addon。Fabric 开发装配复用 Framework 的 `full-assembly.gradle`：Threatening Fabric JAR 进入 `modules`，Human/Damage/Healing 的 server JAR 进入 `serverLibraries`，Qi-Variants 的 shared/server JAR 进入 `localRuntime`：

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

实现范围和 1.20.1 原版生物清单见 [TODO.md](TODO.md)。计划文档位于 [docs/wiki/Implementation-Plan.md](docs/wiki/Implementation-Plan.md)。Fabric 构建需要先生成相邻 Human Addon 的 shared/server JAR；源码联调参数仍为 `-PmeridianUseSourceHuman=true`，但 Human Fabric 与本仓库存在完整装配循环时应使用已构建产物。
