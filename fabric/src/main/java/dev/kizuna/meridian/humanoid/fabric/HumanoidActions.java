package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TargetDistanceBand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

/** 把一次战术决策落到施放请求与移动指令上；施放失败按式累计，连续失败后暂停该式。 */
final class HumanoidActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_brain");
    private static final double ASSAULT_LANDING_MARGIN = 0.95;
    private static final double LANDING_DEVIATION_TOLERANCE = 2.0;
    private static final long FLIGHT_TIME_TOLERANCE_MILLIS = 100;
    private static final double HOLD_RANGE_MARGIN = 0.5;
    private static final double HOLD_RANGE_WIDTH = 4.0;
    private static final double MILLIS_PER_TICK = 50.0;
    /** 站位离射程边和盯上距离再往里留的余量（格）：100 格正是客户端画掠夺者的边。 */
    private static final double STANDOFF_MARGIN = 4.0;
    /** 站位距离带的宽度（格）：带里停下出式，不为一两格来回挪。 */
    private static final double STANDOFF_WIDTH = 4.0;

    /** 一次决策执行所需的实体、大脑状态、身体、候选与对手。 */
    record Turn(Mob mob, HumanoidMind mind, BodySnapshot body, HumanoidRepertoires.Readout readout,
                Optional<HumanoidOpponent> opponent) { }

    /** 一次跃步安全校验所需的预测、落点与首击时刻。 */
    record LeapPlan(Repertoire.Strike strike, TechniqueLeaps.Preview preview, Vec3 expectedLanding,
                    long expectedFlightMillis, long firstHitMillis) { }

    private HumanoidActions() {
        // 纯执行，状态都在 HumanoidMind。
    }

    static void execute(Turn turn, Decision decision) {
        // 两条通道在同一服务端主线程顺序执行；先处理技法，再落移动。
        // 这样突击会先确认跃步成功，再在同一 tick 起手。
        if (decision.movement() != Decision.Movement.DODGE) {
            lowerShield(turn.mob());
        }
        executeTechnique(turn, decision);
        executeMovement(turn, decision);
    }

    private static void executeTechnique(Turn turn, Decision decision) {
        // 技法通道只负责正式功法请求，不因失败改写移动通道。
        switch (decision.action()) {
            case INTERRUPT -> cast(turn, decision.technique().orElseThrow(), decision.movement());
            case STRIKE -> strike(turn, decision);
            case PREPARE -> prepare(turn, decision.technique().orElseThrow(), decision.movement());
            case RECOVER -> recover(turn, decision.technique().orElseThrow(), decision.movement());
            default -> {
                // 纯移动动作由另一条通道执行。
            }
        }
    }

    private static void executeMovement(Turn turn, Decision decision) {
        // 移动通道独立落地；KEEP 在施放中保留上次计划，失败施放也不把实体钉在原地。
        switch (decision.movement()) {
            case KEEP -> keep(turn);
            case VANILLA -> turn.mind().release();
            case FOLLOW -> follow(turn, decision);
            case HOLD_RANGE -> holdRange(turn);
            case STAY -> turn.mind().direct(HumanoidMotion.Directive.STAYING);
            case FACE -> turn.mind().direct(HumanoidMotion.Directive.FACING);
            case RETREAT -> retreat(turn);
            case ASSAULT -> assault(turn);
            case KITE -> kite(turn);
            case DODGE -> dodge(turn);
        }
    }

    private static void strike(Turn turn, Decision decision) {
        // 在可选打击式里按情境与本实体种子抽一式；都不合适时继续接近。
        if (decision.technique().isPresent()) {
            cast(turn, decision.technique().orElseThrow(), decision.movement());
            return;
        }
        Optional<String> technique = chooseStrike(turn).map(Repertoire.Strike::technique);
        if (technique.isPresent()) {
            cast(turn, technique.orElseThrow(), decision.movement());
        }
    }

    private static void prepare(Turn turn, String technique, Decision.Movement movement) {
        // 准备式只提交技法请求，边走还是保持跃步带由移动通道决定。
        boolean started = cast(turn, technique, movement);
        if (!started || turn.opponent().isEmpty()) {
            return;
        }
        BodySnapshot updated = HumanoidTechniqueHost.prepare(turn.mob()).body().currentBody();
        HumanoidCastPlanner.prepare(turn.mob(), updated, turn.readout(), turn.opponent().orElseThrow(), turn.mind())
                .ifPresent(turn.mind()::planCast);
    }

    private static void recover(Turn turn, String technique, Decision.Movement movement) {
        // 调息只提交技法请求，撤退姿态仍可沿退路移动。
        cast(turn, technique, movement);
    }

    private static void dodge(Turn turn) {
        // 副手持盾时举盾原地格挡；否则能跃步就跃出范围，同时走向出口；腿力不够出腿的不跃步，只走出去。
        Mob mob = turn.mob();
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
        Vec3 point = exit.get().point();
        var means = turn.readout().means();
        boolean casting = means.contains(Fighter.Means.CASTING);
        boolean weakLegs = means.contains(Fighter.Means.WEAK_LEGS);
        if (!casting && !weakLegs && TechniqueLeaps.preview(mob, point).isPresent()) {
            TechniqueLeaps.leapToward(mob, point);
        }
        turn.mind().direct(HumanoidMotion.Directive.moveTo(point, HumanoidMotion.Pace.FLEE));
    }

    private static void assault(Turn turn) {
        // 先求解并确认实际起跳，再在同一 tick 起手；失败起跳不能伪造空中施放。
        LivingEntity opponent = opponentEntity(turn);
        Vec3 landing = assaultLanding(turn.mob(), opponent);
        var preview = TechniqueLeaps.preview(turn.mob(), landing);
        turn.mind().direct(HumanoidMotion.Directive.FOLLOWING);
        if (preview.isEmpty()) {
            return;
        }
        TechniqueLeaps.Preview leapPreview = preview.orElseThrow();
        long flightMillis = leapPreview.flightMillis();
        // 落地距离用于所有候选的初筛；首击时刻随式不同，选中后另验。
        Vec3 predictedTarget = predictedTargetPosition(opponent.position(), opponent.getDeltaMovement(), flightMillis);
        double expectedDistance = horizontalDistance(landing, predictedTarget);
        Optional<Repertoire.Strike> strike = chooseStrike(turn, expectedDistance).filter(candidate -> {
            LeapPlan plan = new LeapPlan(candidate, leapPreview, landing, flightMillis,
                    candidate.firstHitMillis());
            return plannedLeapSafe(turn, plan);
        });
        if (strike.isEmpty()) {
            return;
        }
        TechniqueLeaps.Jump jump = TechniqueLeaps.leapToward(turn.mob(), landing);
        if (jump != TechniqueLeaps.Jump.LEAP) {
            return;
        }
        cast(turn, strike.get().technique(), Decision.Movement.FOLLOW);
    }

    static Vec3 assaultLanding(Mob mob, LivingEntity opponent) {
        // 突击落点固定在目标身前，预报与真实起跳共用同一位置。
        double standOff = HumanoidBrain.config().response().assaultStandOff();
        return opponent.position().add(HumanoidMotion.away(mob, opponent).scale(standOff));
    }

    private static void kite(Turn turn) {
        // 只向本次决策确认可达的退路跃步，再沿同一路径走开。
        turn.mind().retreatPoint().ifPresent(destination -> TechniqueLeaps.leapToward(turn.mob(), destination));
        retreat(turn);
    }

    private static void follow(Turn turn, Decision decision) {
        // 对敌时会连发的不往前凑，站在射程边上；明确交还原版攻击的动作释放移动控制，其余施放或追击继续由大脑跟住对手。
        Optional<HumanoidMotion.Directive> standoff = standoff(turn);
        if (standoff.isPresent()) {
            turn.mind().direct(standoff.orElseThrow());
            return;
        }
        boolean vanillaOffense = HumanoidFighters.vanillaMeans(turn.mob())
                .contains(Fighter.Means.VANILLA_OFFENSE);
        boolean ordinaryAttack = decision.action() == Decision.Action.APPROACH
                || decision.action() == Decision.Action.STRIKE && decision.technique().isEmpty();
        if (vanillaOffense && (turn.readout().repertoire().strikes().isEmpty() || ordinaryAttack)) {
            turn.mind().release();
            return;
        }
        turn.mind().direct(HumanoidMotion.Directive.FOLLOWING);
    }

    private static Optional<HumanoidMotion.Directive> standoff(Turn turn) {
        // 对敌、目标看得见且在八格外（连发放得了的距离）、有连发可放（冷却中也算）时：
        // 站位取连发射程和盯上距离里近的那个，再留余量；远了走近，近了退开，带里停下出式。
        // 八格内连发放不了，照近身规则出手，不一味往墙角退着挨打；看不见的照旧跟上去找，撤退和无路自卫不走这里。
        OptionalDouble reach = turn.readout().volleyReach();
        boolean engaged = turn.mind().stance() == HumanoidAssessment.Stance.ENGAGE;
        boolean volleyable = turn.readout().context().combat().target()
                .filter(target -> target.visibility() == TechniqueSelector.Visibility.VISIBLE)
                .filter(target -> target.distanceBand() != TargetDistanceBand.NEAR)
                .isPresent();
        if (reach.isEmpty() || !engaged || !volleyable) {
            return Optional.empty();
        }
        double notice = turn.mob().getAttributeValue(Attributes.FOLLOW_RANGE);
        double maximum = Math.max(0, Math.min(reach.getAsDouble(), notice) - STANDOFF_MARGIN);
        double minimum = Math.max(0, maximum - STANDOFF_WIDTH);
        return Optional.of(HumanoidMotion.Directive.holdRange(minimum, maximum));
    }

    private static void holdRange(Turn turn) {
        // 会连发的按射程站位；其余距离带下界保护跃步最小距离和施法型的补放门槛，上界给直接接近留下确定性余量。
        Optional<HumanoidMotion.Directive> standoff = standoff(turn);
        if (standoff.isPresent()) {
            turn.mind().direct(standoff.orElseThrow());
            return;
        }
        double reach = turn.readout().repertoire().strikes().stream()
                .mapToDouble(Repertoire.Strike::reach).max().orElse(0);
        double leap = turn.readout().repertoire().timing().leapMinimumDistance();
        double minimum = Math.max(Math.max(reach, leap), casterRefreshGate(turn.mob(), turn.body()))
                + HOLD_RANGE_MARGIN;
        double maximum = minimum + HOLD_RANGE_WIDTH;
        turn.mind().direct(HumanoidMotion.Directive.holdRange(minimum, maximum));
    }

    static double casterRefreshGate(Mob mob, BodySnapshot body) {
        // 施法型物种接敌前待在附伤补放门槛外，第一次附伤之后还补得上（C6c SPEC 5.4）；别的物种和不会补放的记 0。
        if (!(mob instanceof SpellcasterIllager)) {
            return 0;
        }
        return HumanoidRepertoires.refreshGate(body);
    }

    private static void keep(Turn turn) {
        // 技法进行中保留移动计划；没有施放时 HOLD 退化为原地等待。
        if (!turn.readout().means().contains(Fighter.Means.CASTING)) {
            turn.mind().direct(HumanoidMotion.Directive.STAYING);
        }
    }

    private static void retreat(Turn turn) {
        // 战术与执行共用已验证的目的地；无退路时停止旧导航。
        var directive = turn.mind().retreatPoint()
                .map(destination -> HumanoidMotion.Directive.moveTo(destination, HumanoidMotion.Pace.FLEE))
                .orElse(HumanoidMotion.Directive.STAYING);
        turn.mind().direct(directive);
    }

    private static boolean cast(Turn turn, String technique, Decision.Movement movement) {
        // 僵直中不发请求，记下等僵直结束补决策；原版法术施放中也不发（C6c SPEC 4.4），等下一次决策；
        // 有对手时以它为目标，并把移动通道转换为 INPUT 水平向量。
        Mob mob = turn.mob();
        var service = TechniqueFabric.service();
        if (service.staggerRemainingMillis(mob.getUUID()) > 0) {
            turn.mind().castBlockedByStagger();
            return false;
        }
        if (HumanoidEvokerSpells.castingSpell(mob)) {
            return false;
        }
        HorizontalDirection direction = direction(turn, movement);
        var request = CastRequest.newBuilder()
                .setBodyId(turn.body().id().value())
                .setTechnique(reference(turn, technique))
                .setCastId(UUID.randomUUID().toString())
                .setDirection(direction);
        turn.opponent().ifPresent(opponent -> request.setTargetId(opponent.entity().getStringUUID()));
        CastOutcome outcome = castWithWeapon(turn, technique, request.build());
        if (failed(outcome)) {
            HumanoidBrainConfig.ResponseRules rules = HumanoidBrain.config().response();
            long now = turn.body().settledAt().elapsedMillis();
            turn.mind().failed(technique, now, rules.failureLimit(), rules.failureBlockMillis());
            turn.mind().noteFailure(technique + " " + outcome.getFailure() + " " + outcome.getFrameworkFailure()
                    + " " + outcome.getReason());
            logFailure(mob, technique, outcome);
            return false;
        }
        turn.mind().succeeded(technique);
        HumanoidEvokerSpells.castStarted(mob, request.getTechnique(), request.getCastId());
        return true;
    }

    private static HorizontalDirection direction(Turn turn, Decision.Movement movement) {
        // INPUT 冲量沿移动意图取水平向量；没有目标时沿实体当前朝向。
        Vec3 vector;
        if (turn.opponent().isEmpty()) {
            vector = turn.mob().getLookAngle().multiply(1, 0, 1);
        } else {
            LivingEntity opponent = turn.opponent().orElseThrow().entity();
            if (movement == Decision.Movement.RETREAT || movement == Decision.Movement.KITE
                    || movement == Decision.Movement.DODGE) {
                vector = HumanoidMotion.away(turn.mob(), opponent);
            } else if (movement == Decision.Movement.FOLLOW || movement == Decision.Movement.ASSAULT) {
                vector = opponent.position().subtract(turn.mob().position()).multiply(1, 0, 1);
            } else {
                vector = turn.mob().getLookAngle().multiply(1, 0, 1);
            }
        }
        Vec3 normalized;
        if (vector.lengthSqr() == 0) {
            normalized = Vec3.ZERO;
        } else {
            normalized = vector.normalize();
        }
        return HorizontalDirection.newBuilder()
                .setX(normalized.x)
                .setZ(normalized.z)
                .build();
    }

    private static Optional<Repertoire.Strike> chooseStrike(Turn turn) {
        // 选择器只抽一次，再从同一份候选映射到首击与距离事实。
        return chooseStrike(turn, turn.readout().context());
    }

    private static Optional<Repertoire.Strike> chooseStrike(Turn turn, double expectedDistance) {
        // 突击按预计落点的距离重新计算权重，避免用起跳前距离误筛长距离式。
        var situation = turn.readout().context().situation();
        var adjustedSituation = new TechniqueSelector.Situation(situation.nowMillis(), situation.selfHpRatio(),
                situation.selfQiRatio(), expectedDistance, situation.targetState(),
                situation.effectiveLegForceNewtons());
        var adjustedContext = new TechniqueSelector.Context(turn.readout().context().registry(), adjustedSituation,
                turn.readout().context().availability(), turn.readout().context().combat());
        return chooseStrike(turn, adjustedContext);
    }

    private static Optional<Repertoire.Strike> chooseStrike(Turn turn, TechniqueSelector.Context context) {
        // 选择器只抽一次，再从同一份候选映射到首击与距离事实。
        var chosen = TechniqueSelector.choose(context, turn.readout().strikes(),
                HumanoidBrain.seed(turn.mob(), turn.mind()));
        return chosen.flatMap(reference -> turn.readout().repertoire().strikes().stream()
                .filter(strike -> strike.technique().equals(reference.getId()))
                .findFirst());
    }

    static boolean plannedLeapSafe(Turn turn, LeapPlan plan) {
        // 落点偏差、目标移动和来袭覆盖均在提交跃步前验证。
        TechniqueLeaps.Preview preview = plan.preview();
        Vec3 actualLanding = preview.landing();
        Vec3 expectedLanding = plan.expectedLanding();
        long expectedFlightMillis = plan.expectedFlightMillis();
        long firstHitMillis = plan.firstHitMillis();
        if (actualLanding.distanceTo(expectedLanding) > LANDING_DEVIATION_TOLERANCE) {
            return false;
        }
        long flightMillis = preview.flightMillis();
        long flightDifference = Math.abs(flightMillis - expectedFlightMillis);
        if (flightDifference > FLIGHT_TIME_TOLERANCE_MILLIS) {
            return false;
        }
        LivingEntity opponent = opponentEntity(turn);
        var technique = reference(turn, plan.strike().technique());
        var definition = turn.readout().context().registry().find(technique).orElseThrow();
        if (!landingStrikeInRange(plan, definition, opponent.position(), opponent.getDeltaMovement())) {
            return false;
        }
        long incomingHorizonMillis = flightMillis + firstHitMillis;
        return !overlappingIncoming(turn, incomingHorizonMillis);
    }

    static boolean landingStrikeInRange(LeapPlan plan, TechniqueDefinition definition,
                                         Vec3 targetPosition, Vec3 velocityPerTick) {
        // 起跳和起手同时发生；先确认落地时或之后还有打击，再核对目标是否仍在落点的起手范围。
        var preview = plan.preview();
        long flightMillis = preview.flightMillis();
        boolean hitAtOrAfterLanding = definition.getEffectsList().stream()
                .filter(effect -> effect.getAtMillis() >= flightMillis)
                .anyMatch(effect -> switch (effect.getOperationCase()) {
                    case WEAPON_STRIKE, LEG_STRIKE, LIMB_STRIKE, GRAPPLE, LAUNCH_PROJECTILE -> true;
                    default -> false;
                });
        if (!hitAtOrAfterLanding) {
            return false;
        }
        Vec3 predictedTarget = predictedTargetAtLandingOrFirstHit(targetPosition, velocityPerTick,
                flightMillis, plan.firstHitMillis());
        double landingDistance = horizontalDistance(preview.landing(), predictedTarget);
        return landingDistance <= plan.strike().reach() + ASSAULT_LANDING_MARGIN;
    }

    static Vec3 predictedTargetAtLandingOrFirstHit(Vec3 position, Vec3 velocityPerTick,
                                                   long flightMillis, long firstHitMillis) {
        // 首击早于落地时看落地位置；首击晚于落地时仍等到首击，二者都是从同一次起跳起算。
        long horizonMillis = Math.max(flightMillis, firstHitMillis);
        return predictedTargetPosition(position, velocityPerTick, horizonMillis);
    }

    static Vec3 predictedTargetPosition(Vec3 position, Vec3 velocityPerTick, long horizonMillis) {
        // getDeltaMovement 的单位是格/tick，预报时长也从毫秒换成 tick。
        double horizonTicks = horizonMillis / MILLIS_PER_TICK;
        return position.add(velocityPerTick.scale(horizonTicks));
    }

    private static boolean overlappingIncoming(Turn turn, long horizonMillis) {
        return turn.opponent().flatMap(HumanoidOpponent::cast)
                .flatMap(forecast -> forecast.strikes().stream()
                        .filter(window -> window.millisUntilHit() <= horizonMillis)
                        .filter(window -> HumanoidStrikeEstimates.covers(turn.mob(), opponentEntity(turn),
                                forecast.active(), window.strike()))
                        .findFirst())
                .isPresent();
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        // 落点与目标运动只比较水平距离，符合功法打击的平面范围判定。
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.hypot(dx, dz);
    }

    private static CastOutcome castWithWeapon(Turn turn, String technique, CastRequest request) {
        // 选中后才换兵；真实起手重新检查持物和费用，拒绝或异常均还原原物品。
        var exchange = Optional.ofNullable(turn.readout().exchanges().get(technique));
        exchange.ifPresent(HumanoidWayfarerWeapons.Exchange::apply);
        try {
            CastOutcome outcome = TechniqueFabric.service().cast(turn.mob().getUUID(), request);
            if (failed(outcome)) {
                exchange.ifPresent(HumanoidWayfarerWeapons.Exchange::restore);
            }
            return outcome;
        } catch (RuntimeException failure) {
            exchange.ifPresent(HumanoidWayfarerWeapons.Exchange::restore);
            throw failure;
        }
    }

    private static boolean failed(CastOutcome outcome) {
        // 功法失败或框架失败任一非空即失败。
        return outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED
                || outcome.getFrameworkFailure() != FailureCode.FAILURE_CODE_UNSPECIFIED;
    }

    private static void logFailure(Mob mob, String technique, CastOutcome outcome) {
        // 只在逐条决策日志打开时记下失败原因。
        if (HumanoidBrain.config().schedule().log() != HumanoidBrainConfig.DecisionLog.VERBOSE) {
            return;
        }
        LOGGER.info("类人施放失败 subject={} technique={} failure={} framework={} reason={}",
                mob.getStringUUID(), technique, outcome.getFailure(), outcome.getFrameworkFailure(),
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

    private static void raiseShield(Mob mob) {
        // 已在举盾时不重举，免得格挡计时清零。
        if (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND) {
            return;
        }
        mob.startUsingItem(InteractionHand.OFF_HAND);
    }

    private static void lowerShield(Mob mob) {
        // 只放下副手的盾，不打断别的物品使用。
        if (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND
                && mob.getUseItem().getItem() instanceof ShieldItem) {
            mob.stopUsingItem();
        }
    }
}
