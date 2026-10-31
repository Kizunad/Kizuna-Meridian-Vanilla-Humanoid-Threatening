package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;

/** 战力事实：从身体快照与持物读出境界档、身体状况和手段；没有经脉身体的一方按凡人档计。 */
final class HumanoidFighters {
    static final String ARM_MUSCLE = "human:arm_muscle";
    static final String LEG_MUSCLE = "human:leg_muscle";
    private static final Fighter.Condition UNBODIED = new Fighter.Condition(1, 1, 0, 1, 1);

    private HumanoidFighters() {
        // 纯读取，不缓存身体。
    }

    static Fighter self(LivingEntity entity, BodySnapshot body, Set<Means> techniqueMeans) {
        // 自己的打击式可施放与否由调用方按绑定、费用和持物算好，这里补原版手段。
        var means = vanillaMeans(entity);
        means.addAll(techniqueMeans);
        return new Fighter(Fighter.tier(body.realm()), condition(body), means);
    }

    static Fighter opponent(LivingEntity entity, Optional<TechniqueService.Observation> observation,
                            TechniqueRegistry registry) {
        // 对手只看知识与冷却，不替它绑定或算费用；没有功法宿主的按凡人档与完好身体计。
        var means = vanillaMeans(entity);
        if (observation.isEmpty()) {
            return new Fighter(Fighter.MORTAL_TIER, UNBODIED, means);
        }
        BodySnapshot body = observation.get().body();
        if (knowsReadyStrike(body, registry)) {
            means.add(Means.CASTABLE_STRIKE);
        }
        if (observation.get().cast().isPresent()) {
            means.add(Means.CASTING);
        }
        return new Fighter(Fighter.tier(body.realm()), condition(body), means);
    }

    private static boolean knowsReadyStrike(BodySnapshot body, TechniqueRegistry registry) {
        // 会一个冷却已过的打击式即视为有此手段。
        var progress = TechniqueState.read(body);
        long now = body.settledAt().elapsedMillis();
        return progress.getKnownList().stream()
                .map(known -> registry.find(known.getTechnique()))
                .flatMap(Optional::stream)
                .filter(definition -> definition.getAiHints().getSupport().getRole() == AiRole.AI_ROLE_UNSPECIFIED)
                .anyMatch(definition -> now >= progress.getCooldownsOrDefault(definition.getId(), 0));
    }

    static EnumSet<Means> vanillaMeans(LivingEntity entity) {
        // 原版攻击属性、远程或施法能力算进攻手段；主手攻击加成算近战武器。
        var means = EnumSet.noneOf(Means.class);
        if (entity.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                || entity instanceof RangedAttackMob || entity instanceof SpellcasterIllager) {
            means.add(Means.VANILLA_OFFENSE);
        }
        var main = entity.getMainHandItem();
        if (main.getAttributeModifiers(EquipmentSlot.MAINHAND).containsKey(Attributes.ATTACK_DAMAGE)) {
            means.add(Means.MELEE_WEAPON);
        }
        if (main.getItem() instanceof ProjectileWeaponItem) {
            means.add(Means.RANGED_WEAPON);
        }
        if (entity.getOffhandItem().getItem() instanceof ShieldItem) {
            means.add(Means.SHIELD);
        }
        return means;
    }

    static Fighter.Condition condition(BodySnapshot body) {
        // 完好率、最弱存活部位、真元占容，以及两臂、两腿中较强一侧的有效力量比。
        return new Fighter.Condition(Math.min(1, body.partHpRatio()), weakestPartHpRatio(body), qiRatio(body),
                strengthRatio(body, ARM_MUSCLE), strengthRatio(body, LEG_MUSCLE));
    }

    static double weakestPartHpRatio(BodySnapshot body) {
        // 已归零的部位不再参与；全部归零时为 0。
        Map<Anatomy.PartId, Double> maximum = body.structure().parts().stream()
                .collect(Collectors.toMap(Anatomy.PartDefinition::id, Anatomy.PartDefinition::maxHp));
        return body.partStates().stream()
                .filter(part -> part.hp() > 0)
                .mapToDouble(part -> part.hp() / maximum.get(part.id()))
                .min()
                .orElse(0);
    }

    static double weakestPartHp(BodySnapshot body) {
        // 来袭剂量比的分母：最弱存活部位当前 HP；全部归零时为 0。
        return body.partStates().stream()
                .mapToDouble(AnatomyState.Part::hp)
                .filter(hp -> hp > 0)
                .min()
                .orElse(0);
    }

    static double qiRatio(BodySnapshot body) {
        // 框架容量以体积计，库存按实际密度换算占容；外壁不能支付功法费用。
        double capacity = body.structure().lumenVolume();
        if (capacity == 0) {
            return 0;
        }
        double occupied = body.meridianStates().stream().mapToDouble(state -> state.lumenQi().volume()).sum();
        return Math.min(1, occupied / capacity);
    }

    static double strengthRatio(BodySnapshot body, String tag) {
        // 有效能力已含真实曲线、伤势和疲劳；取较强一侧，没有该类肌肉时按完好计。
        return tissues(body, tag).stream()
                .filter(tissue -> tissue.capacity().strengthNewtons() > 0)
                .mapToDouble(tissue -> TissueCapabilities.available(body, tissue.id()).strengthNewtons()
                        / tissue.capacity().strengthNewtons())
                .max()
                .orElse(1);
    }

    static double fatigueRatio(BodySnapshot body, Set<Anatomy.TissueId> tissueIds) {
        // 所绑定肌肉的平均疲劳比：1 减去有效力量与基础力量之比；没有可比肌肉时为 0。
        Map<Anatomy.TissueId, Anatomy.TissueDefinition> definitions = body.structure().tissues().stream()
                .collect(Collectors.toMap(Anatomy.TissueDefinition::id, Function.identity()));
        return 1 - tissueIds.stream()
                .map(definitions::get)
                .filter(tissue -> tissue != null && tissue.capacity().strengthNewtons() > 0)
                .mapToDouble(tissue -> TissueCapabilities.available(body, tissue.id()).strengthNewtons()
                        / tissue.capacity().strengthNewtons())
                .average()
                .orElse(1);
    }

    private static List<Anatomy.TissueDefinition> tissues(BodySnapshot body, String tag) {
        // 按 Human 肌肉标签筛组织。
        return body.structure().tissues().stream()
                .filter(tissue -> tissue.tags().stream().anyMatch(candidate -> candidate.value().equals(tag)))
                .toList();
    }
}
