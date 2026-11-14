package dev.kizuna.meridian.humanoid.server;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

    public VolumeTable plus(VolumeTable added) {
        // 把另一张表的卷接在同一物种同一档的后面；这一档已经有的卷不重复加，先到的那条（连同概率）算数。
        var merged = new HashMap<String, Map<HumanoidTier, List<Grant>>>();
        species.forEach((entityId, tiers) -> merged.computeIfAbsent(entityId, VolumeTable::noTiers).putAll(tiers));
        added.species.forEach((entityId, tiers) -> tiers.forEach((tier, grants) -> merged
                .computeIfAbsent(entityId, VolumeTable::noTiers)
                .merge(tier, grants, VolumeTable::appended)));
        return new VolumeTable(merged);
    }

    private static Map<HumanoidTier, List<Grant>> noTiers(String entityId) {
        // 合并时每个物种一张可写的档位表。
        return new EnumMap<>(HumanoidTier.class);
    }

    private static List<Grant> appended(List<Grant> present, List<Grant> added) {
        // 原有顺序不动，新卷按给出的顺序接在后面。
        var grants = new ArrayList<>(present);
        for (Grant grant : added) {
            if (grants.stream().noneMatch(listed -> listed.volume().equals(grant.volume()))) {
                grants.add(grant);
            }
        }
        return List.copyOf(grants);
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

    public void requireOrdered(Function<String, List<String>> prerequisites) {
        // 授卷按表里的顺序逐卷学，前置卷没学会时后面那卷会被功法库拒绝；
        // 所以同一物种同一档里，每一卷的前置卷都要排在它前面，否则启动就失败并报清两卷。
        species.forEach((entityId, tiers) -> tiers.forEach((tier, grants) -> requireOrdered(
                entityId + " " + tier.id(), grants.stream().map(Grant::volume).toList(), prerequisites)));
    }

    private static void requireOrdered(String where, List<String> order,
                                       Function<String, List<String>> prerequisites) {
        // 逐卷查前置卷在这一档里的位置：没列或排在后面都拒绝。
        for (int index = 0; index < order.size(); index++) {
            String volume = order.get(index);
            for (String required : prerequisites.apply(volume)) {
                int position = order.indexOf(required);
                if (position < 0) {
                    throw HumanoidErrors.invalid(HumanoidErrors.BRAIN + ": " + where + " 的学卷 " + volume
                            + " 要先学前置卷 " + required + "，这一档没列");
                }
                if (position > index) {
                    throw HumanoidErrors.invalid(HumanoidErrors.BRAIN + ": " + where + " 的学卷 " + volume
                            + " 排在它的前置卷 " + required + " 前面");
                }
            }
        }
    }

    static boolean granted(Grant grant, UUID entity) {
        // 同一实体对同一卷总落在同一个桶，重复分配结果不变。
        long bucket = Math.floorMod(Objects.hash(entity, grant.volume()), BUCKETS);
        return bucket < grant.chance() * BUCKETS;
    }
}
