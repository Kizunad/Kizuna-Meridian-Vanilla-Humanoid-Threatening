package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
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
        var verdict = species.bodyRules().death();
        if (verdict != null) {
            return payload -> {
                var prior = HealthProtobuf.decodeContext(payload).getBody();
                var result = HealthProtobuf.decodeResolution(human.resolve(payload));
                var wounded = wounded(prior, result);
                var decision = result.toBuilder().setLethal(verdict.lethal(wounded, result)).build();
                return HealthProtobuf.encodeResolution(decision);
            };
        }
        HumanoidDeathRule rule = species.definition()
                .map(HumanoidSpeciesDefinition::deathRule)
                .orElse(HumanoidDeathRule.HUMAN);
        if (rule == HumanoidDeathRule.NEVER) {
            return context -> survive(human.resolve(context));
        }
        return human;
    }

    private static BodySnapshot wounded(BodySnapshot prior, DamageResolution resolution) {
        // 裁决读取本击所有损失扣除后的局部 HP；权威提交、保命和死亡释放仍由框架处理。
        var body = prior.toBuilder();
        for (var loss : resolution.getLossesList()) {
            var target = loss.getTarget();
            double amount = loss.getHp();
            switch (target.getModuleCase()) {
                case PART_ID -> body.getPartStatesBuilderList().stream()
                        .filter(part -> part.getId().equals(target.getPartId())).findFirst()
                        .ifPresent(part -> part.setHp(Math.max(0, part.getHp() - amount)));
                case TISSUE_ID -> body.getTissueStatesBuilderList().stream()
                        .filter(tissue -> tissue.getId().equals(target.getTissueId())).findFirst()
                        .ifPresent(tissue -> tissue.setHp(Math.max(0, tissue.getHp() - amount)));
                case ORGAN_ID -> body.getOrganStatesBuilderList().stream()
                        .filter(organ -> organ.getId().equals(target.getOrganId())).findFirst()
                        .ifPresent(organ -> organ.setHp(Math.max(0, organ.getHp() - amount)));
                default -> throw HumanoidErrors.invalid("伤害目标缺少部位、组织或器官");
            }
        }
        return body.build();
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
