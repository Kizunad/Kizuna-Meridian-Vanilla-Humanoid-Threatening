package dev.kizuna.meridian.humanoid.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 每个物种按出生档位写明学哪几卷、概率多少；卷可以来自任何已安装的功法目录。 */
public record VolumeTable(Map<String, Map<HumanoidTier, List<Grant>>> species) {
    private static final int BUCKETS = 1_000_000;

    /** 一卷与学到它的概率。 */
    public record Grant(String volume, double chance) { }

    public VolumeTable {
        // 表在加载后只读，档位列表一并复制。
        var copied = new HashMap<String, Map<HumanoidTier, List<Grant>>>();
        species.forEach((entityId, tiers) -> copied.put(entityId, Map.copyOf(tiers)));
        species = Map.copyOf(copied);
    }

    public List<String> volumes(String entityId, HumanoidTier tier, UUID entity) {
        // 未列出的物种或档位不学卷，默认表不写凡人档，凡人因此一卷不学；
        // 概率按实体 UUID 与卷 ID 确定性分桶，不动身体生成器的随机序列。
        return species.getOrDefault(entityId, Map.of()).getOrDefault(tier, List.of()).stream()
                .filter(grant -> granted(grant, entity))
                .map(Grant::volume)
                .toList();
    }

    public Set<String> allVolumes() {
        // 启动时据此确认表里每一卷都已在功法注册表中。
        return species.values().stream()
                .flatMap(tiers -> tiers.values().stream())
                .flatMap(List::stream)
                .map(Grant::volume)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireInstalled(Predicate<String> installed) {
        // 表里每一卷都必须已在功法注册表中，缺卷直接拒绝装配。
        for (String volume : allVolumes()) {
            if (!installed.test(volume)) {
                throw HumanoidErrors.invalid(HumanoidErrors.BRAIN + ": 学卷未安装 " + volume);
            }
        }
    }

    static boolean granted(Grant grant, UUID entity) {
        // 同一实体对同一卷总落在同一个桶，重复分配结果不变。
        long bucket = Math.floorMod(Objects.hash(entity, grant.volume()), BUCKETS);
        return bucket < grant.chance() * BUCKETS;
    }
}
