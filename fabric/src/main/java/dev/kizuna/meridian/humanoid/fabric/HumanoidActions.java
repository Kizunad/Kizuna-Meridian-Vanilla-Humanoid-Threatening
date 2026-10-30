package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/** 把一次战术决策落到施放请求与移动指令上；施放失败按式累计，连续失败后暂停该式。 */
final class HumanoidActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_brain");
    private static final double FLEE_ARRIVED_DISTANCE = 2;

    /** 一次决策执行所需的实体、大脑状态、身体、候选与对手。 */
    record Turn(MobEntity mob, HumanoidMind mind, BodySnapshot body, HumanoidRepertoires.Readout readout,
                Optional<HumanoidOpponent> opponent) { }

    private HumanoidActions() {
        // 纯执行，状态都在 HumanoidMind。
    }

    static void execute(Turn turn, Decision decision) {
        // 不躲时先放下举着的盾；随后按动作施放或换移动指令。
        if (decision.action() != Decision.Action.DODGE) {
            lowerShield(turn.mob());
        }
        switch (decision.action()) {
            case INTERRUPT -> castAndFollow(turn, decision.technique().orElseThrow());
            case STRIKE -> strike(turn);
            case PREPARE -> prepare(turn, decision.technique().orElseThrow());
            case RECOVER -> recover(turn, decision.technique().orElseThrow());
            case DODGE -> dodge(turn);
            case ABSORB -> turn.mind().direct(HumanoidMotion.Directive.STAYING);
            case ASSAULT -> assault(turn);
            case KITE -> kite(turn);
            case APPROACH -> approach(turn);
            case RETREAT -> retreat(turn);
            case HOLD -> hold(turn);
            case RELEASE -> turn.mind().release();
        }
    }

    private static void castAndFollow(Turn turn, String technique) {
        // 打断与打击都朝对手施放并贴住它。
        cast(turn, technique);
        turn.mind().direct(HumanoidMotion.Directive.FOLLOWING);
    }

    private static void strike(Turn turn) {
        // 在可选打击式里按情境与本实体种子抽一式；都不合适时继续接近。
        var readout = turn.readout();
        var chosen = TechniqueSelector.choose(readout.context(), readout.strikes(),
                HumanoidBrain.seed(turn.mob(), turn.mind()));
        if (chosen.isEmpty()) {
            approach(turn);
            return;
        }
        castAndFollow(turn, chosen.get().getId());
    }

    private static void prepare(Turn turn, String technique) {
        // 接敌时原地准备，免得准备期间走进轻步与跃步的最小距离；撤退时边准备边走开。
        cast(turn, technique);
        if (turn.mind().stance() == HumanoidAssessment.Stance.RETREAT) {
            retreat(turn);
            return;
        }
        turn.mind().direct(HumanoidMotion.Directive.STAYING);
    }

    private static void recover(Turn turn, String technique) {
        // 恢复式原地施放。
        cast(turn, technique);
        turn.mind().direct(HumanoidMotion.Directive.STAYING);
    }

    private static void dodge(Turn turn) {
        // 副手持盾时举盾原地格挡；否则能跃步就跃出范围，同时走向出口。
        MobEntity mob = turn.mob();
        if (HumanoidFighters.vanillaMeans(mob).contains(Fighter.Means.SHIELD)) {
            raiseShield(mob);
            turn.mind().direct(HumanoidMotion.Directive.STAYING);
            return;
        }
        var exit = turn.opponent().flatMap(opponent -> HumanoidStrikeEstimates.exit(mob, opponent));
        if (exit.isEmpty()) {
            turn.mind().direct(HumanoidMotion.Directive.STAYING);
            return;
        }
        Vec3d point = exit.get().point();
        if (TechniqueLeaps.preview(mob, point).isPresent()) {
            TechniqueLeaps.leapToward(mob, point);
        }
        turn.mind().direct(HumanoidMotion.Directive.moveTo(point, HumanoidMotion.Pace.FLEE));
    }

    private static void assault(Turn turn) {
        // 跃向对手身前、隔开配置的距离，落地后贴住。
        LivingEntity opponent = opponentEntity(turn);
        double standOff = HumanoidBrain.config().response().assaultStandOff();
        Vec3d landing = opponent.getPos().add(HumanoidMotion.away(turn.mob(), opponent).multiply(standOff));
        TechniqueLeaps.leapToward(turn.mob(), landing);
        turn.mind().direct(HumanoidMotion.Directive.FOLLOWING);
    }

    private static void kite(Turn turn) {
        // 背向对手跃步，再沿同一方向走开。
        Vec3d destination = HumanoidMotion.retreatPoint(turn.mob(), opponentEntity(turn));
        TechniqueLeaps.leapToward(turn.mob(), destination);
        turn.mind().direct(HumanoidMotion.Directive.moveTo(destination, HumanoidMotion.Pace.FLEE));
    }

    private static void approach(Turn turn) {
        // 原版会打的交给原版目标与攻击 Goal；其余由大脑跟住对手。
        if (HumanoidFighters.vanillaMeans(turn.mob()).contains(Fighter.Means.VANILLA_OFFENSE)) {
            turn.mind().release();
            return;
        }
        turn.mind().direct(HumanoidMotion.Directive.FOLLOWING);
    }

    private static void retreat(Turn turn) {
        // 上一个撤退点仍在背离对手的方向且未走到时沿用，免得每次决策换方向。
        if (stillFleeing(turn)) {
            return;
        }
        Vec3d destination = HumanoidMotion.retreatPoint(turn.mob(), opponentEntity(turn));
        turn.mind().direct(HumanoidMotion.Directive.moveTo(destination, HumanoidMotion.Pace.FLEE));
    }

    private static boolean stillFleeing(Turn turn) {
        // 当前指令是逃离走点，离目的地还远，且目的地比自己离对手更远。
        var directive = turn.mind().directive();
        if (directive.movement() != HumanoidMotion.Movement.MOVE_TO
                || directive.pace() != HumanoidMotion.Pace.FLEE) {
            return false;
        }
        Vec3d destination = directive.destination().orElseThrow();
        Vec3d threat = opponentEntity(turn).getPos();
        Vec3d position = turn.mob().getPos();
        return position.distanceTo(destination) > FLEE_ARRIVED_DISTANCE
                && destination.squaredDistanceTo(threat) > position.squaredDistanceTo(threat);
    }

    private static void hold(Turn turn) {
        // 施放中保持当前指令；否则原地不动。
        if (turn.readout().means().contains(Fighter.Means.CASTING)) {
            return;
        }
        turn.mind().direct(HumanoidMotion.Directive.STAYING);
    }

    private static void cast(Turn turn, String technique) {
        // 僵直中不发请求；有对手时以它为目标，失败按式累计。
        MobEntity mob = turn.mob();
        var service = TechniqueFabric.service();
        if (service.staggerRemainingMillis(mob.getUuid()) > 0) {
            return;
        }
        var request = CastRequest.newBuilder()
                .setBodyId(turn.body().id().value())
                .setTechnique(reference(turn, technique))
                .setCastId(UUID.randomUUID().toString());
        turn.opponent().ifPresent(opponent -> request.setTargetId(opponent.entity().getUuidAsString()));
        CastOutcome outcome = service.cast(mob.getUuid(), request.build());
        if (failed(outcome)) {
            HumanoidBrainConfig.ResponseRules rules = HumanoidBrain.config().response();
            long now = turn.body().settledAt().elapsedMillis();
            turn.mind().failed(technique, now, rules.failureLimit(), rules.failureBlockMillis());
            logFailure(mob, technique, outcome);
            return;
        }
        turn.mind().succeeded(technique);
    }

    private static boolean failed(CastOutcome outcome) {
        // 功法失败或框架失败任一非空即失败。
        return outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED
                || outcome.getFrameworkFailure() != FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    private static void logFailure(MobEntity mob, String technique, CastOutcome outcome) {
        // 只在逐条决策日志打开时记下失败原因。
        if (HumanoidBrain.config().schedule().log() != HumanoidBrainConfig.DecisionLog.VERBOSE) {
            return;
        }
        LOGGER.info("类人施放失败 subject={} technique={} failure={} framework={} reason={}",
                mob.getUuidAsString(), technique, outcome.getFailure(), outcome.getFrameworkFailure(),
                outcome.getReason());
    }

    private static TechniqueReference reference(Turn turn, String technique) {
        // 决策里的式一定来自本次读出的已学列表。
        return turn.readout().known().stream()
                .map(KnownTechnique::getTechnique)
                .filter(reference -> reference.getId().equals(technique))
                .findFirst()
                .orElseThrow();
    }

    private static LivingEntity opponentEntity(Turn turn) {
        // 对敌动作必有对手。
        return turn.opponent().orElseThrow().entity();
    }

    private static void raiseShield(MobEntity mob) {
        // 已在举盾时不重举，免得格挡计时清零。
        if (mob.isUsingItem() && mob.getActiveHand() == Hand.OFF_HAND) {
            return;
        }
        mob.setCurrentHand(Hand.OFF_HAND);
    }

    private static void lowerShield(MobEntity mob) {
        // 只放下副手的盾，不打断别的物品使用。
        if (mob.isUsingItem() && mob.getActiveHand() == Hand.OFF_HAND
                && mob.getActiveItem().getItem() instanceof ShieldItem) {
            mob.clearActiveItem();
        }
    }
}
