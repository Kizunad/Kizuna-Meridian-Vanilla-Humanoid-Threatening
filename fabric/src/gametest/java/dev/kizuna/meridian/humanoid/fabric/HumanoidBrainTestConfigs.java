package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.VolumeTable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** GameTest 批次用的大脑配置：破门旧用例只发破门卷，批次结束换回启动时装配的配置。 */
final class HumanoidBrainTestConfigs {
    private static final String DOOR_BREAKER_PREFIX = "meridian_door_breaker:";
    private static HumanoidBrainConfig previous;

    private HumanoidBrainTestConfigs() {
        // 只在批次前后切换全局配置。
    }

    static void doorBreakerOnly() {
        // 保留启动配置的其余各节，学卷表每档只留破门卷，基础三卷不发。
        HumanoidBrainConfig current = HumanoidBrain.config();
        install(current, doorBreakerVolumes(current.volumes()));
    }

    static void withVolumes(String entityId, List<VolumeTable.Grant> grants) {
        // 只把一个物种的每一档换成给定的卷，其他物种照启动配置。
        HumanoidBrainConfig current = HumanoidBrain.config();
        var species = new HashMap<>(current.volumes().species());
        var tiers = new EnumMap<Realm, List<VolumeTable.Grant>>(Realm.class);
        species.getOrDefault(entityId, Map.of()).keySet().forEach(realm -> tiers.put(realm, grants));
        species.put(entityId, tiers);
        install(current, new VolumeTable(species));
    }

    private static void install(HumanoidBrainConfig current, VolumeTable volumes) {
        // 记下批次前的配置，再装只换了学卷表的配置。
        previous = current;
        HumanoidBrain.configure(new HumanoidBrainConfig(current.schedule(), current.threat(), current.assessment(),
                current.response(), volumes));
    }

    static void restore() {
        // 换回批次前的配置；批次前未切换时不动。
        if (previous == null) {
            return;
        }
        HumanoidBrain.configure(previous);
        previous = null;
    }

    private static VolumeTable doorBreakerVolumes(VolumeTable table) {
        // 物种与档位不变，只过滤每档的卷。
        var species = new HashMap<String, Map<Realm, List<VolumeTable.Grant>>>();
        table.species().forEach((entityId, tiers) -> {
            var kept = new EnumMap<Realm, List<VolumeTable.Grant>>(Realm.class);
            tiers.forEach((realm, grants) -> kept.put(realm, doorBreakerGrants(grants)));
            species.put(entityId, kept);
        });
        return new VolumeTable(species);
    }

    private static List<VolumeTable.Grant> doorBreakerGrants(List<VolumeTable.Grant> grants) {
        // 只留破门目录的卷。
        return grants.stream()
                .filter(grant -> grant.volume().startsWith(DOOR_BREAKER_PREFIX))
                .toList();
    }
}
