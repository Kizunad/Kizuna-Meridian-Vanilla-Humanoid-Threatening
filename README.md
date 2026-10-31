# Kizuna's Meridian Vanilla Humanoid Threatening

本仓库只为 Minecraft Java 版 1.20.1 原版活着的类人生物（生人）提供 Meridian 经脉身体内容。类人亡灵不属于本仓库范围。

支持村民、流浪商人、掠夺者、卫道士、唤魔者、女巫和幻术师的游炁/真炁变体。纯 Java 生成器和 Fabric 宿主已经实现：身体按需创建并写入实体 NBT，时间和区域库存来自 Human 环境服务，死亡/自然消失通过世界释放账本回写区域；跨维度和区块卸载保留身体。已有身体的村民雷击时保留村民和原身体，照常着火、受伤；无身体村民保持原版女巫转化。OP 可用 `/meridian body status <target>` 检查快照，`/meridian humanoid spawn` 按档位生成（写法见 [Framework 指令页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki/Commands)）。七种生人按学卷表学功法，由类人大脑判断撤退或对敌并施放；规则与配置 `meridian-humanoid-brain.toml` 见计划文档「类人大脑」一节。

构建需要 Java 17。相邻的 Framework、Qi-Variants、功法库、Human（server/shared）、Damage 与 PA Mobs-Addon 在 `settings.gradle` 中按 Framework 统一装配清单以源码组合（`kizunaWorkspace.includeSource`），Gradle、Loom 与第三方版本取自 Framework 的版本目录。Fabric 开发运行复用 Framework 的 `full-assembly.gradle`，按清单装入整套产物：已组合的模块使用源码输出，Human Fabric、破门斩、基础功法等其余模组读取各仓已构建的安装 JAR：

```bash
./gradlew build
python3 "scripts/check_docs.py"
```

实现范围和当前 7 种生人清单见 [TODO.md](TODO.md)。计划文档位于 [docs/wiki/Implementation-Plan.md](docs/wiki/Implementation-Plan.md)；其他模组登记自己的类人物种见 [docs/wiki/Species-Registration.md](docs/wiki/Species-Registration.md)。编译不再需要预先构建 Human 的 shared/server JAR；GameTest 与开发客户端仍需相邻仓库先构建各自的安装 JAR。
