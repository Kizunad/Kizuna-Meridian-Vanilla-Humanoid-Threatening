package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import java.util.Optional;
import net.minecraft.world.entity.Mob;

/** 行矢处境门槛；持物、费用、冷却和承接时窗仍由功法库判定。 */
final class HumanoidWayfarerAi {
    static final String PREFIX = "meridian_wayfarer:";
    private static final String PARTHIAN = PREFIX + "parthian";
    private static final double MELEE_DISTANCE = 3;
    private static final double HALF_HP = 0.5;
    private static final int FAN_ENEMY_COUNT = 2;

    private HumanoidWayfarerAi() {
        // 只筛处境，不改变目录数值或为生物另造功法。
    }

    static boolean allowed(Mob mob, BodySnapshot body, TechniqueDefinition definition,
                           Optional<HumanoidWayfarerFacts.Target> target) {
        // 训练式永不进入 AI；远程式没有观测目标时不猜测可射击事实。
        String id = definition.getId();
        if (!id.startsWith(PREFIX)) {
            return true;
        }
        if (target.isEmpty() || id.equals(PREFIX + "dry_draw")) {
            return false;
        }
        var facts = target.orElseThrow();
        if (id.equals(PARTHIAN)) {
            return facts.approaching() && body.partHpRatio() < HALF_HP;
        }
        if (id.equals(PREFIX + "convergence")) {
            return definition.getFollowUp().getOnlyFollowUp();
        }
        if (!facts.visible()) {
            return id.equals(PREFIX + "arrow_rain");
        }
        return visibleChoice(mob, id, facts);
    }

    private static boolean visibleChoice(Mob mob, String id, HumanoidWayfarerFacts.Target facts) {
        // 远处静止用重射，中距用连发，贴身按当前武器反击，横风额外覆盖近处多个敌人。
        double distance = facts.distance();
        if (id.equals(PREFIX + "full_draw") || id.equals(PREFIX + "kneeling")) {
            return distance > HumanoidWayfarerFacts.MEDIUM_DISTANCE && facts.speedMetersPerSecond() == 0;
        }
        if (id.equals(PREFIX + "repeater") || id.equals(PREFIX + "pearls")) {
            return distance >= HumanoidWayfarerFacts.NEAR_DISTANCE
                    && distance <= HumanoidWayfarerFacts.MEDIUM_DISTANCE;
        }
        if (id.equals(PREFIX + "gale")) {
            return distance < HumanoidWayfarerFacts.NEAR_DISTANCE && facts.nearbyEnemies() >= FAN_ENEMY_COUNT
                    || distance <= MELEE_DISTANCE && HumanoidWayfarerWeapons.holds(mob,
                    HumanoidWayfarerWeapons.CROSSBOWS);
        }
        return id.equals(PREFIX + "bow_strike") && distance <= MELEE_DISTANCE
                && HumanoidWayfarerWeapons.holds(mob, HumanoidWayfarerWeapons.BOWS);
    }

    static Decision continueOrEscape(Fighter self, HumanoidAssessment.Stance stance,
                                     HumanoidRepertoires.Readout readout, Decision ordinary) {
        // 双臂虚脱始终避战；躲避和硬抗保留普通战术优先级。
        double exhausted = HumanoidBrain.config().assessment().retreat().armStrengthRatio();
        if (self.condition().armStrengthRatio() < exhausted
                || ordinary.action() == Decision.Action.DODGE || ordinary.action() == Decision.Action.ABSORB) {
            return ordinary;
        }
        var candidates = readout.strikes().stream()
                .filter(known -> known.getTechnique().getId().startsWith(PREFIX))
                .filter(known -> TechniqueSelector.score(readout.context(), known) > 0)
                .toList();
        boolean escape = candidates.stream().anyMatch(known -> known.getTechnique().getId().equals(PARTHIAN));
        if (escape) {
            return Decision.cast(Decision.Action.STRIKE, PARTHIAN, "被逼近且不足半血，回马拉开");
        }
        if (stance == HumanoidAssessment.Stance.ENGAGE && self.has(Fighter.Means.CASTING)
                && !candidates.isEmpty() && ordinary.action() == Decision.Action.HOLD) {
            return Decision.of(Decision.Action.STRIKE, "行矢进入承接时窗");
        }
        return ordinary;
    }
}
