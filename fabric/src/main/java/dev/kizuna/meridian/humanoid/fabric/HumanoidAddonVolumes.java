package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.config.TomlFile;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfigLoader;
import dev.kizuna.meridian.humanoid.server.VolumeTable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

/**
 * 功法包往学卷表里登记自己的卷：在自己的 {@code fabric.mod.json} 的 {@code custom} 里用
 * {@value #CUSTOM_KEY} 指向模组内的一份 TOML 片段，写法和配置文件的 {@code [volumes]} 一节相同。
 *
 * <p>本模组因此不用依赖各功法包。片段在启动时并进配置文件的学卷表，之后只有这一张表：
 * 分卷、启动核对、对外展示都读它。</p>
 */
final class HumanoidAddonVolumes {
    static final String CUSTOM_KEY = "meridian_humanoid:volumes";

    private HumanoidAddonVolumes() {
        // 只在启动时读一遍各模组的声明。
    }

    static VolumeTable joined(VolumeTable configured) {
        // 用真实 Fabric 容器读模组资源。
        return joined(configured, FabricLoader.getInstance().getAllMods());
    }

    static VolumeTable joined(VolumeTable configured, Collection<ModContainer> installed) {
        // 按模组 ID 排序逐份接上，结果不随加载顺序变；配置文件写过的卷照配置。
        List<ModContainer> declaring = installed.stream()
                .filter(mod -> mod.getMetadata().containsCustomValue(CUSTOM_KEY))
                .sorted(Comparator.comparing(mod -> mod.getMetadata().getId()))
                .toList();
        VolumeTable volumes = configured;
        for (ModContainer mod : declaring) {
            volumes = volumes.plus(read(mod));
        }
        return volumes;
    }

    private static VolumeTable read(ModContainer mod) {
        // 声明必须是一个字符串路径，指向本模组里的 .toml；读不到、写错都带上模组和路径报出来，启动失败。
        String id = mod.getMetadata().getId();
        CustomValue value = mod.getMetadata().getCustomValue(CUSTOM_KEY);
        if (value.getType() != CustomValue.CvType.STRING) {
            throw new IllegalArgumentException(id + ":custom." + CUSTOM_KEY + "：必须为字符串");
        }
        String source = id + ":" + value.getAsString();
        Path file = mod.findPath(value.getAsString())
                .filter(path -> value.getAsString().endsWith(".toml"))
                .orElseThrow(() -> new IllegalArgumentException(source + "：模组内找不到这份 .toml 学卷片段"));
        try {
            return HumanoidBrainConfigLoader.addonVolumes(TomlFile.read(file));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(source + "：" + failure.getMessage(), failure);
        }
    }
}
