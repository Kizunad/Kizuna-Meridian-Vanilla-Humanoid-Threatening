package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.server.BodyDamageHandler;

import java.util.Objects;

/** 按物种死亡规则选择伤害裁定：损伤分配都走 Human 人体规则，永不死亡的物种只去掉致死结论。 */
public final class HumanoidDamagePolicy {
    private HumanoidDamagePolicy() {
        // 纯工厂，不持有身体。
    }

    public static BodyDamageHandler forSpecies(HumanoidSpecies species) {
        // 内置物种没有登记定义，按人体规则判死。
        Objects.requireNonNull(species, "species");
        var human = new HumanDamage(species.entityId());
        HumanoidDeathRule rule = species.definition()
                .map(HumanoidSpeciesDefinition::deathRule)
                .orElse(HumanoidDeathRule.HUMAN);
        if (rule == HumanoidDeathRule.NEVER) {
            return context -> survive(human.resolve(context));
        }
        return human;
    }

    static byte[] survive(byte[] resolution) {
        // 剂量、蔓延与各目标损失原样保留，只把致死改为否。
        DamageResolution decided = HealthProtobuf.decodeResolution(resolution);
        DamageResolution kept = decided.toBuilder()
                .setLethal(false /* lethal */)
                .build();
        return HealthProtobuf.encodeResolution(kept);
    }
}
