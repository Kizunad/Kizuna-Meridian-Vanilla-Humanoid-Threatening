package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.GuardResponse;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
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
        var tiers = new EnumMap<HumanoidTier, List<VolumeTable.Grant>>(HumanoidTier.class);
        species.getOrDefault(entityId, Map.of()).keySet().forEach(tier -> tiers.put(tier, grants));
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

    static void holdRangeDuringPreparation() {
        // 发箭回归固定准备式的距离窗口，防止随机跟随走入八米内而永久失去连弩候选。
        HumanoidBrainConfig current = HumanoidBrain.config();
        previous = current;
        var concurrency = new HumanoidBrainConfig.ConcurrencyRules(1, current.concurrency().allowRecoveryMovement());
        HumanoidBrain.configure(new HumanoidBrainConfig(current.schedule(), current.threat(), current.assessment(),
                current.response(), current.volumes(), concurrency));
    }

    static void disableAutomaticGuard() {
        // 只关闭大脑自动起格挡，正式格挡功法和体力结算仍由对应测试覆盖。
        HumanoidBrainConfig current = HumanoidBrain.config();
        previous = current;
        var threat = current.threat();
        var guard = threat.guard();
        var reactions = new EnumMap<HumanoidTier, GuardResponse.Reaction>(HumanoidTier.class);
        guard.reactions().forEach((tier, reaction) ->
                reactions.put(tier, new GuardResponse.Reaction(0, reaction.delayMillis())));
        var disabledGuard = new GuardResponse(guard.range(), guard.angleDegrees(), guard.turnDegreesPerTick(),
                guard.timing(), reactions);
        var disabledThreat = new HumanoidBrainConfig.ThreatRules(threat.memorySeconds(), threat.allyRadius(),
                threat.forgetDistance(), threat.safeDistance(), threat.noticeDistances(), threat.scoring(),
                disabledGuard);
        var disabled = new HumanoidBrainConfig(current.schedule(), disabledThreat, current.assessment(),
                current.response(), current.volumes(), current.concurrency(), current.villager(), current.volumePool());
        HumanoidBrain.configure(disabled);
    }

    private static VolumeTable doorBreakerVolumes(VolumeTable table) {
        // 物种与档位不变，只过滤每档的卷。
        var species = new HashMap<String, Map<HumanoidTier, List<VolumeTable.Grant>>>();
        table.species().forEach((entityId, tiers) -> {
            var kept = new EnumMap<HumanoidTier, List<VolumeTable.Grant>>(HumanoidTier.class);
            tiers.forEach((tier, grants) -> kept.put(tier, doorBreakerGrants(grants)));
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
