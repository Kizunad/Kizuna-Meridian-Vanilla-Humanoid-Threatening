package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Encounter;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig.ConcurrencyRules;
import dev.kizuna.meridian.humanoid.server.HumanoidTactics;
import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueCastTimelines;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.phys.Vec3;

/** 类人大脑：被接管实体按错相周期读敌意、评估、选战术，再由接入适配器落到原版控制上。 */
public final class HumanoidBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_brain");
    private static final long SEQUENCE_SPREAD = 0x9E3779B97F4A7C15L;
    private static final Map<UUID, HumanoidMind> minds = new HashMap<>();
    private static final Map<UUID, Optional<TechniqueService.Observation>> observed = new HashMap<>();
    private static long observedTick = Long.MIN_VALUE;
    private static HumanoidBrainConfig config;

    /** 一式及其情境评分。 */
    private record Scored(TechniqueReference reference, double score) { }

    /** 移动通道计算所需的本 tick 战斗上下文。 */
    private record MovementContext(Mob mob, HumanoidMind mind, BodySnapshot body,
                                   HumanoidAssessment.Stance stance, Fighter self,
                                   Decision decision) { }

    private HumanoidBrain() {
        // 状态按实体 UUID 保存，实体离开世界时清理。
    }

    static void configure(HumanoidBrainConfig next) {
        // 配置按服务端统一装配，个体是否接管在使用时判断。
        config = next;
    }

    static HumanoidBrainConfig config() {
        // 未装配时为空，调用方据此跳过。
        return config;
    }

    public static boolean managed(LivingEntity entity) {
        // 已认领且物种由大脑接管的原版生物，AI 未关闭且存活。
        if (!(entity instanceof Mob mob) || mob.isNoAi() || !mob.isAlive()) {
            return false;
        }
        return HumanoidSpeciesHooks.isLivingHumanoid(mob);
    }

    public static void updateBodyProjection(Mob mob, GoalSelector goals, GoalSelector targets) {
        // 按服务端实体刻刷新投影，使关闭 AI 或被定身的个体也能及时交还原版属性。
        if (!HumanoidSpeciesHooks.excluded(mob)) {
            HumanoidTempo.update(mob);
            return;
        }
        HumanoidTempo.clear(mob);
        if (minds.containsKey(mob.getUUID()) || HumanoidTechniqueHost.hasSession(mob)) {
            HumanoidGoalAdapter.detach(mob, goals, targets);
            HumanoidTechniqueHost.removed(mob);
        }
    }

    public static void tick(Mob mob, GoalSelector goals, GoalSelector targets) {
        // 每 tick 只装接入；真正的双通道决策仍按错相节奏运行，飞行中不改主意。
        if (config == null || !HumanoidFabric.isReady()) {
            return;
        }
        if (!managed(mob)) {
            return;
        }
        HumanoidMind mind = minds.computeIfAbsent(mob.getUUID(), ignored -> new HumanoidMind());
        if (HumanoidCharmController.controlled(mob) && HumanoidCharmController.mortal(mob)) {
            return;
        }
        HumanoidGoalAdapter.install(mob, mind, goals, targets);
        try {
            if (mind.guard.tick(mob, config.threat().guard())) {
                mind.clearCastPlan();
                return;
            }
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            HumanoidFabric.report(mob, unavailable);
        }
        if (checkCastPlan(mob, mind)) {
            return;
        }
        if (((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
            return;
        }
        // 正在放的式后面有能接的式时，承接窗口一开就补一次决策：窗口常常只有半秒，固定节拍会整个错过。
        boolean followUp = followUpOpened(mob, mind);
        if (!followUp && !decisionDue(mob, mind) && !opponentInStrikeDistance(mob, mind)) {
            return;
        }
        mind.clearDecisionWaits();
        try {
            decide(mob, mind, followUp);
        } catch (RuntimeException failure) {
            mind.release();
            HumanoidFabric.report(mob, failure);
        }
    }

    private static boolean decisionDue(Mob mob, HumanoidMind mind) {
        // 按 ID 错相每个间隔决策一次；选中的式因僵直没发出去时，僵直一结束就补一次。
        // 原版近战约一秒一击、每击僵直将近一秒，固定节拍会一直落在僵直里。
        // 原版目标选择刚认出对手时下一个 tick 就接手，不让原版攻击先朝对手走上整整一个间隔。
        if (mind.contactPending()) {
            return true;
        }
        int interval = config.schedule().decisionIntervalTicks();
        if (Math.floorMod((long) mob.tickCount + mob.getId(), interval) == 0) {
            return true;
        }
        return mind.awaitingStaggerEnd() && TechniqueFabric.service().staggerRemainingMillis(mob.getUUID()) == 0;
    }

    private static boolean opponentInStrikeDistance(Mob mob, HumanoidMind mind) {
        // 上次决策时对手还在起手距离外：它进来的那个 tick 就再决策一次，打击式在配置的距离上起手，不等贴脸。
        if (!mind.awaitingStrikeDistance()) {
            return false;
        }
        return mind.opponent().filter(LivingEntity::isAlive)
                .filter(opponent -> mind.takeStrikeDistance(HumanoidMotion.distance(mob, opponent)))
                .isPresent();
    }

    private static boolean followUpOpened(Mob mob, HumanoidMind mind) {
        // 只在等着承接窗口时才读身体时钟；时钟答不出就留给例行节拍。
        if (!mind.awaitingFollowUp()) {
            return false;
        }
        var clock = HumanoidTechniqueHost.clockMillis(mob);
        return clock.isPresent() && mind.takeFollowUp(clock.getAsLong());
    }

    private static Optional<LivingEntity> threat(Mob mob, HumanoidMind mind) {
        // 被魅惑的只打施术者指定的对手；招敌派来的只盯那名玩家，撤走的谁也不打；其余综合打分选。
        if (HumanoidCharmController.controlled(mob)) {
            return Optional.ofNullable(HumanoidCharmController.opponent(mob));
        }
        if (CrisisRaiders.sent(mob)) {
            return CrisisRaiders.quarry(mob);
        }
        return HumanoidThreats.choose(mob, config.threat(), mind.opponent());
    }

    private static void decide(Mob mob, HumanoidMind mind, boolean followUp) {
        // 女巫先处理行药诀；其他生物再找威胁，无威胁路径只查已有会话的恢复式。
        // followUp：这次是承接窗口刚打开补的决策，自己还在施放也照常选式。
        if (mob instanceof Witch witch && HumanoidWitchAi.decide(witch)) {
            mind.release();
            return;
        }
        Optional<LivingEntity> threat = threat(mob, mind);
        if (threat.isEmpty()) {
            idle(mob, mind);
            return;
        }
        var target = threat.orElseThrow();
        mind.engage(target);
        HumanoidNotice.keepEngaged(mob, target);
        if (HumanoidSwordForms.tryCast(mob, target)) {
            mind.clearCastPlan();
            return;
        }
        var opponent = new HumanoidOpponent(target, observe(target));
        boolean casting = HumanoidTechniqueHost.hasActiveCast(mob);
        if (!casting) {
            mind.castIdle();
        } else if (!followUp && !mind.followUpExamined()) {
            mind.followUpExamined(followUpOpening(body(mob)));
        }
        if (casting && !followUp && opponent.cast().isEmpty() && !HumanoidTechniqueHost.castingWayfarer(mob)
                && !HumanoidMeleeGuard.enabled(mob)) {
            return;
        }
        fight(mob, mind, opponent);
    }

    private static OptionalLong followUpOpening(BodySnapshot body) {
        // 正在放的这一式后面，自己会的式里最早什么时候（身体时钟）能接上；没有能接的、或者窗口已经开过了，为空。
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return OptionalLong.empty();
        }
        long now = body.settledAt().elapsedMillis();
        long started = progress.getActiveCast().getStartedMillis();
        return TechniqueCastTimelines.status(TechniqueFabric.service().registry(), body, now)
                .getFollowUpsList().stream()
                .mapToLong(hint -> started + hint.getOpenMillis())
                .filter(opens -> opens > now)
                .min();
    }

    private static void fight(Mob mob, HumanoidMind mind, HumanoidOpponent opponent) {
        // 一次身体读取、选式与处境评估产生一个动作，导航计划只计算一次。
        BodySnapshot body = body(mob);
        long now = body.settledAt().elapsedMillis();
        var readout = HumanoidRepertoires.read(mob, body, Optional.of(opponent),
                technique -> mind.blocked(technique, now));
        Fighter self = HumanoidFighters.self(mob, body, readout.means());
        Fighter other = HumanoidFighters.opponent(opponent.entity(), opponent.observation(),
                TechniqueFabric.service().registry());
        HumanoidThreatScores.seen(opponent.entity(), other);
        double distance = HumanoidMotion.distance(mob, opponent.entity());
        readout.repertoire().strikes().stream().mapToDouble(Repertoire.Strike::reach).max().stream()
                .filter(farthest -> distance > farthest)
                .forEach(mind::awaitStrikeDistance);
        var separation = new HumanoidAssessment.Separation(distance, unpressedSeconds(mob, mind, opponent, now));
        HumanoidAssessment.Stance stance = assess(mob, mind, self, other, separation, readout);
        if (stance == HumanoidAssessment.Stance.RETREAT && mob instanceof Villager villager
                && HumanoidSorties.overlooks(villager, opponent.entity(), config.villager())) {
            // 村民评估不划算、对方又还没冲着他来：当没看见，照没有威胁那样过。
            idle(mob, mind);
            return;
        }
        var incoming = HumanoidStrikeEstimates.incoming(mob, body, opponent);
        var circumstances = combatCircumstances(mob, mind, stance, self);
        if (HumanoidRepertoires.peddlerCanDefend(readout)) {
            circumstances.add(Encounter.Circumstance.DEFENSIVE_STRIKE);
        }
        if (self.condition().armStrengthRatio() < config.assessment().retreat().armStrengthRatio()) {
            circumstances.add(Encounter.Circumstance.EXHAUSTED_ARMS);
        }
        var encounter = new Encounter(self, other, distance, incoming, circumstances);
        Decision ordinary = HumanoidTactics.decide(encounter, stance, readout.repertoire(),
                config.response().tactics());
        Decision techniqueDecision = HumanoidWayfarerAi.continueOrEscape(self, stance, readout, ordinary);
        MovementContext movementContext = new MovementContext(mob, mind, body, stance, self, techniqueDecision);
        Decision decision = movementDecision(movementContext);
        record(mob, mind, stance, decision);
        HumanoidActions.execute(new HumanoidActions.Turn(mob, mind, body, readout, Optional.of(opponent)),
                decision);
        scheduleCastPlan(mob, mind, readout, opponent);
    }

    private static HumanoidAssessment.Stance assess(Mob mob, HumanoidMind mind, Fighter self, Fighter other,
                                                    HumanoidAssessment.Separation separation,
                                                    HumanoidRepertoires.Readout readout) {
        // 行商该撤就撤；「不退」的不比战力；其余比战力，村民再减掉人数上的劣势。
        if (HumanoidRepertoires.peddlerShouldRetreat(readout)) {
            return HumanoidAssessment.Stance.RETREAT;
        }
        if (HumanoidMeleeGuard.enabled(mob) && (HumanoidMeleeGuard.withdrawn(mob)
                || mind.opponent().filter(HumanoidMeleeGuard::holdsCrossbow).isPresent())) {
            return HumanoidMeleeGuard.assess(mob, mind, self, separation);
        }
        if (HumanoidDuties.standsGround(mob)) {
            return HumanoidAssessment.standing(self, config.assessment());
        }
        double handicap = 0;
        if (mob instanceof Villager villager) {
            handicap = HumanoidSorties.handicap(villager, config);
        }
        return HumanoidAssessment.stance(self, other, mind.stance(), separation, config.assessment(), handicap);
    }

    private static double unpressedSeconds(Mob mob, HumanoidMind mind, HumanoidOpponent opponent, long now) {
        // 撤退中对手连着多久没逼近、也没打到我；第一次看到这个对手时没得比，当作刚被逼过。
        var seen = HumanoidPressure.sight(mob, opponent.entity(), now);
        boolean pressed = mind.sighting()
                .map(before -> HumanoidPressure.pressing(mob.position(), before, seen))
                .orElse(true);
        return mind.notePressure(seen, pressed);
    }

    private static void scheduleCastPlan(Mob mob, HumanoidMind mind, HumanoidRepertoires.Readout readout,
                                         HumanoidOpponent opponent) {
        // 已在执行中的准备式只登记一次峰值计划，实际起手仍由逐 tick 检查器决定。
        // 身体在这里重新读：刚执行的动作可能起了新式，决策前读到的那份里还是上一次施放，不能拿它倒排。
        if (mind.castPlan().isPresent()) {
            return;
        }
        HumanoidCastPlanner.prepare(mob, body(mob), readout, opponent, mind).ifPresent(mind::planCast);
    }

    private static boolean checkCastPlan(Mob mob, HumanoidMind mind) {
        // 倒排计划每 tick 校验目标与身体时间，命中失效条件立即清理；到点以后才结清并读身体。
        Optional<HumanoidMind.CastPlan> planned = mind.castPlan();
        if (planned.isEmpty()) {
            return false;
        }
        HumanoidMind.CastPlan plan = planned.get();
        try {
            if (invalidCastPlanTarget(mob, mind, plan)) {
                mind.clearCastPlan();
                return false;
            }
            if (castPlanWaiting(mob, plan)) {
                return true;
            }
            BodySnapshot body = body(mob);
            return evaluateCastPlan(mob, mind, body, plan);
        } catch (RuntimeException failure) {
            mind.clearCastPlan();
            HumanoidFabric.report(mob, failure);
            return false;
        }
    }

    private static boolean castPlanWaiting(Mob mob, HumanoidMind.CastPlan plan) {
        // 还没到点、也没过期时只比较身体时钟，不结清也不读身体；时钟答不出就照旧读身体。
        // 这个时钟与结清后读到的 settled_at 是同一个数，所以到点的那个 tick 不变。
        var clock = HumanoidTechniqueHost.clockMillis(mob);
        if (clock.isEmpty()) {
            return false;
        }
        long now = clock.getAsLong();
        return now <= plan.expiresMillis() && now < plan.dueMillis();
    }

    private static boolean evaluateCastPlan(Mob mob, HumanoidMind mind, BodySnapshot body,
                                            HumanoidMind.CastPlan plan) {
        // 计划到期后才尝试起手；蓄力或飞行仍在进行时保留或清理计划。
        long now = body.settledAt().elapsedMillis();
        if (now > plan.expiresMillis()) {
            mind.clearCastPlan();
            return false;
        }
        if (now < plan.dueMillis()) {
            return true;
        }
        if (((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
            mind.clearCastPlan();
            return false;
        }
        var progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return executeCastPlan(mob, mind, body, plan, now);
        }
        if (now <= progress.getActiveCast().getRecoveryEndMillis()) {
            return true;
        }
        mind.clearCastPlan();
        return false;
    }

    private static boolean invalidCastPlanTarget(Mob mob, HumanoidMind mind, HumanoidMind.CastPlan plan) {
        // 目标切换、死亡、卸载或受击都使原来的落点和峰值时间失去意义。
        LivingEntity currentTarget = mob.getTarget();
        boolean targetChanged = currentTarget != null && currentTarget != plan.target();
        boolean targetTooFar = mob.distanceTo(plan.target()) > config.threat().forgetDistance();
        return mind.opponent().filter(target -> target == plan.target()).isEmpty()
                || targetChanged || targetTooFar
                || !plan.target().isAlive() || plan.target().isRemoved()
                || plan.target().level() != mob.level() || mob.hurtTime > 0;
    }

    private static boolean executeCastPlan(Mob mob, HumanoidMind mind, BodySnapshot body,
                                           HumanoidMind.CastPlan plan, long now) {
        // 计划到期后重新读候选、验证落点，成功起跳才在同一 tick 起手。
        var opponent = new HumanoidOpponent(plan.target(), observe(plan.target()));
        var readout = HumanoidRepertoires.read(mob, body, Optional.of(opponent),
                technique -> mind.blocked(technique, now));
        Optional<Repertoire.Strike> strike = plannedStrike(readout, plan);
        if (strike.isEmpty()) {
            mind.clearCastPlan();
            return false;
        }
        Vec3 landing = HumanoidActions.assaultLanding(mob, plan.target());
        Optional<TechniqueLeaps.Preview> preview = TechniqueLeaps.preview(mob, landing);
        if (preview.isEmpty()) {
            mind.clearCastPlan();
            return false;
        }
        HumanoidActions.Turn turn = new HumanoidActions.Turn(mob, mind, body, readout, Optional.of(opponent));
        HumanoidActions.LeapPlan leapPlan = new HumanoidActions.LeapPlan(strike.get(), preview.get(),
                plan.expectedLanding(), plan.flightMillis(), plan.firstHitMillis());
        if (!HumanoidActions.plannedLeapSafe(turn, leapPlan)) {
            mind.clearCastPlan();
            return false;
        }
        TechniqueLeaps.Jump jump = TechniqueLeaps.leapToward(mob, landing);
        if (jump != TechniqueLeaps.Jump.LEAP) {
            mind.clearCastPlan();
            return false;
        }
        Decision decision = Decision.cast(Decision.Action.STRIKE, plan.technique(), Decision.Movement.FOLLOW,
                plan.reason());
        record(mob, mind, mind.stance(), decision);
        HumanoidActions.execute(turn, decision);
        mind.clearCastPlan();
        return true;
    }

    private static Optional<Repertoire.Strike> plannedStrike(HumanoidRepertoires.Readout readout,
                                                              HumanoidMind.CastPlan plan) {
        // 计划只接受最初选定的式与首击时刻，候选变化时回到普通决策。
        return readout.repertoire().strikes().stream()
                .filter(candidate -> candidate.technique().equals(plan.technique()))
                .filter(candidate -> candidate.firstHitMillis() == plan.firstHitMillis())
                .findFirst();
    }

    private static Decision movementDecision(MovementContext context) {
        // 技法通道先完成选择；移动通道再按局面独立决定。
        // 准备式保留保持跃步带和直冲两种策略。
        HumanoidMind mind = context.mind();
        Fighter self = context.self();
        Decision decision = context.decision();
        if (decision.action() == Decision.Action.PREPARE) {
            return prepareMovement(context);
        }
        if (decision.action() == Decision.Action.RECOVER && mind.opponent().isPresent()) {
            return recoveryMovement(context);
        }
        if (decision.action() == Decision.Action.HOLD && self.has(Fighter.Means.CASTING)) {
            return castingMovement(context);
        }
        return decision;
    }

    private static Decision castingMovement(MovementContext context) {
        // 普通施放冻结技法通道，但移动通道仍按当前姿态更新。
        // 飞行和起跳仍由框架互斥。
        HumanoidMind mind = context.mind();
        BodySnapshot body = context.body();
        HumanoidAssessment.Stance stance = context.stance();
        Decision decision = context.decision();
        Optional<AiRole> role = activeRole(body);
        if (role.filter(value -> value == AiRole.AI_ROLE_PREPARE).isPresent()) {
            return prepareMovement(context);
        }
        if (role.filter(value -> value == AiRole.AI_ROLE_RECOVER).isPresent()
                && mind.opponent().isPresent()) {
            return recoveryMovement(context);
        }
        if (stance == HumanoidAssessment.Stance.RETREAT && mind.retreatPoint().isPresent()) {
            return constrain(decision.withMovement(Decision.Movement.RETREAT), ConcurrencyRules.CastPhase.STRIKE);
        }
        if (stance == HumanoidAssessment.Stance.ENGAGE && mind.opponent().isPresent()) {
            return constrain(decision.withMovement(Decision.Movement.FOLLOW), ConcurrencyRules.CastPhase.STRIKE);
        }
        return constrain(decision.withMovement(Decision.Movement.KEEP), ConcurrencyRules.CastPhase.STRIKE);
    }

    private static Decision prepareMovement(MovementContext context) {
        // 准备式撤退时沿退路移动；会补放附伤的施法型物种总是保持距离带（带在补放门槛外），
        // 其余在保持跃步带与跟随之间确定选择。
        Decision decision = context.decision();
        if (context.stance() == HumanoidAssessment.Stance.RETREAT) {
            return constrain(decision.withMovement(Decision.Movement.RETREAT),
                    ConcurrencyRules.CastPhase.PREPARE);
        }
        boolean holdRange = HumanoidActions.casterRefreshGate(context.mob(), context.body()) > 0
                || seed(context.mob(), context.mind()).nextDouble() < config.concurrency().holdRangeProbability();
        Decision.Movement movement;
        if (holdRange) {
            movement = Decision.Movement.HOLD_RANGE;
        } else {
            movement = Decision.Movement.FOLLOW;
        }
        return constrain(decision.withMovement(movement), ConcurrencyRules.CastPhase.PREPARE);
    }

    private static Decision recoveryMovement(MovementContext context) {
        // 调息时按配置选择沿退路移动或原地恢复。
        Decision decision = context.decision();
        Decision.Movement movement;
        if (config.concurrency().allowRecoveryMovement()) {
            movement = Decision.Movement.RETREAT;
        } else {
            movement = Decision.Movement.STAY;
        }
        return constrain(decision.withMovement(movement), ConcurrencyRules.CastPhase.RECOVER);
    }

    private static Decision constrain(Decision decision, ConcurrencyRules.CastPhase phase) {
        // 执行前再次应用框架约束，避免未来新增移动分支绕过施放中禁跃规则。
        if (config.concurrency().allows(phase, decision.movement())) {
            return decision;
        }
        return decision.withMovement(Decision.Movement.KEEP);
    }

    private static Optional<AiRole> activeRole(BodySnapshot body) {
        // 当前施放的角色从同一身体进度与目录读取，不按具体功法 ID 分支。
        var progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return Optional.empty();
        }
        return TechniqueFabric.service().registry().find(progress.getActiveCast().getRequest().getTechnique())
                .map(definition -> definition.getAiHints().getSupport().getRole());
    }

    private static Set<Encounter.Circumstance> combatCircumstances(Mob mob, HumanoidMind mind,
                                                                  HumanoidAssessment.Stance stance, Fighter self) {
        // 退路怎么找见 escape；连一个可去的点都没有才算受困。
        var result = circumstances(mob);
        if (stance != HumanoidAssessment.Stance.RETREAT) {
            return result;
        }
        LivingEntity opponent = mind.opponent().orElseThrow();
        if (HumanoidThreats.pursued(mob, opponent)) {
            result.add(Encounter.Circumstance.PURSUED);
        }
        if (mob.onGround()) {
            mind.planRetreat(escape(mob, opponent, mind, self));
        }
        if (mind.cornered()) {
            result.add(Encounter.Circumstance.RETREAT_BLOCKED);
        }
        return result;
    }

    private static Optional<Vec3> escape(Mob mob, LivingEntity opponent, HumanoidMind mind, Fighter self) {
        // 原版攻击者受困后只有找到通往安全距离的退路才恢复逃离，避免刚向敌人迈一步又退回同一墙角；
        // 腿力够出防守腿法的要退到安全距离，短距离来回拉开不算有退路，退不开就迎上去出腿；
        // 腿力不够、防守腿法都在冷却或眼下用不上时照旧找短退点躲开拉距离，
        // 连短退点都没有就贴墙横着让开，横着也让不开、对手又还远时沿墙换到另一个角，不站着等腿法就绪。
        boolean castable = self.has(Fighter.Means.CASTABLE_DEFENSE);
        double safeDistance = 0;
        if (castable || mind.cornered() && self.has(Fighter.Means.VANILLA_OFFENSE)) {
            safeDistance = config.response().tactics().safeDistance();
        }
        var previous = mind.directive();
        var retreat = HumanoidMotion.retreatPoint(mob, opponent, previous, safeDistance);
        boolean awaitingDefense = self.has(Fighter.Means.KNOWN_DEFENSE) && !castable;
        if (retreat.isPresent() || !awaitingDefense) {
            return retreat;
        }
        var sidestep = HumanoidMotion.sidestepPoint(mob, opponent, previous);
        if (sidestep.isPresent()) {
            return sidestep;
        }
        return HumanoidMotion.cornerSwitchPoint(mob, opponent, previous);
    }

    private static void idle(Mob mob, HumanoidMind mind) {
        // 无威胁：已有会话且会恢复式的才读身体评估恢复；其余只交还原版控制、不记决策。
        mind.disengage();
        if (HumanoidTechniqueHost.hasActiveCast(mob)) {
            return;
        }
        if (!HumanoidTechniqueHost.knowsRole(mob, AiRole.AI_ROLE_RECOVER)) {
            mind.release();
            return;
        }
        BodySnapshot body = body(mob);
        long now = body.settledAt().elapsedMillis();
        var readout = HumanoidRepertoires.read(mob, body, Optional.empty(),
                technique -> mind.blocked(technique, now));
        Decision decision = HumanoidTactics.idle(HumanoidFighters.self(mob, body, readout.means()),
                readout.repertoire());
        if (decision.action() == Decision.Action.RELEASE && mind.directive() == HumanoidMotion.Directive.RELEASED) {
            return;
        }
        record(mob, mind, HumanoidAssessment.Stance.ENGAGE, decision);
        HumanoidActions.execute(new HumanoidActions.Turn(mob, mind, body, readout, Optional.empty()), decision);
    }

    private static BodySnapshot body(Mob mob) {
        // 经可信宿主结清时间后的当前快照，学卷也在此补发。
        return HumanoidTechniqueHost.prepare(mob).body().currentBody();
    }

    static Optional<TechniqueService.Observation> observe(LivingEntity entity) {
        // 同一服务器 tick 内同一对手只观察一次。
        long tick = HumanoidFabric.server().getTickCount();
        if (tick != observedTick) {
            observed.clear();
            observedTick = tick;
        }
        return observed.computeIfAbsent(entity.getUUID(), uuid -> TechniqueFabric.service().observe(uuid));
    }

    private static Set<Encounter.Circumstance> circumstances(Mob mob) {
        // 飞行、着地与刚受击直接读实体状态。
        var circumstances = EnumSet.noneOf(Encounter.Circumstance.class);
        if (((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
            circumstances.add(Encounter.Circumstance.LEAPING);
        }
        if (mob.onGround()) {
            circumstances.add(Encounter.Circumstance.ON_GROUND);
        }
        if (mob.hurtTime > 0) {
            circumstances.add(Encounter.Circumstance.UNDER_ATTACK);
        }
        return circumstances;
    }

    private static void record(Mob mob, HumanoidMind mind, HumanoidAssessment.Stance stance,
                               Decision decision) {
        // 写进环形记录；开日志时每条决策一行。
        mind.decided(mob.level().getGameTime(), stance, decision);
        if (config.schedule().log() == HumanoidBrainConfig.DecisionLog.VERBOSE) {
            LOGGER.info("类人决策 subject={} action={} technique={} reason={}", mob.getStringUUID(),
                    decision.action(), decision.technique().orElse("-"), decision.reason());
        }
    }

    static Optional<TechniqueReference> topStrike(Mob mob, LivingEntity target, BodySnapshot body) {
        // 与决策同一份候选与情境，取情境评分最高的打击式；决策本身按权重抽样，GameTest 据此检查情境权重。
        HumanoidMind mind = minds.computeIfAbsent(mob.getUUID(), ignored -> new HumanoidMind());
        var opponent = new HumanoidOpponent(target, observe(target));
        long now = body.settledAt().elapsedMillis();
        var readout = HumanoidRepertoires.read(mob, body, Optional.of(opponent),
                technique -> mind.blocked(technique, now));
        return readout.strikes().stream()
                .map(known -> new Scored(known.getTechnique(), TechniqueSelector.score(readout.context(), known)))
                .filter(scored -> scored.score() > 0)
                .max(Comparator.comparingDouble(Scored::score).thenComparing(scored -> scored.reference().getId()))
                .map(Scored::reference);
    }

    static Random seed(Mob mob, HumanoidMind mind) {
        // 每实体种子：世界种子与 UUID 定实体，决策序号乘散布常数后混入；
        // 只差低位的种子首个随机数几乎相同，同一实体每次决策会抽中同一式。
        long worldSeed = ((ServerLevel) mob.level()).getSeed();
        return new Random(HumanoidBodyGenerator.seedFor(worldSeed ^ mind.sequence() * SEQUENCE_SPREAD, mob.getUUID()));
    }

    static boolean directing(Mob mob) {
        // 大脑给了非原版指令，或施放中仍有对手要盯。
        if (HumanoidCharmController.controlled(mob) && HumanoidCharmController.opponent(mob) == null) {
            return true;
        }
        HumanoidMind mind = minds.get(mob.getUUID());
        if (mind == null || !managed(mob)) {
            return false;
        }
        return mind.directive() != HumanoidMotion.Directive.RELEASED
                || HumanoidTechniqueHost.hasActiveCast(mob) && mind.opponent().isPresent();
    }

    static void move(Mob mob, HumanoidMotion.Speeds speeds) {
        // 近战在出手段结束前站定，近身守卫的地面起手可跟进；收式执行当前指令。
        if (HumanoidCharmController.follow(mob)) {
            return;
        }
        HumanoidMind mind = minds.get(mob.getUUID());
        if (mind == null) {
            return;
        }
        boolean meleeGuard = HumanoidMeleeGuard.enabled(mob);
        if (mind.opponent().isEmpty() && meleeGuard) {
            HumanoidMotion.apply(mob, mind, HumanoidMotion.Directive.STAYING, speeds);
            return;
        }
        var pursuit = HumanoidMeleeGuard.warmupPursuit(mob, mind);
        if (pursuit.isPresent()) {
            HumanoidMotion.followWarmup(mob, mind, pursuit.getAsDouble());
            return;
        }
        if (holdsForStrike(mob, mind)) {
            HumanoidMotion.apply(mob, mind, HumanoidMotion.Directive.STAYING, speeds);
            return;
        }
        var directive = mind.directive();
        var selectedSpeeds = HumanoidMeleeGuard.speeds(mob, mind, speeds);
        if (directive == HumanoidMotion.Directive.RELEASED) {
            HumanoidMotion.apply(mob, mind, HumanoidMotion.Directive.FOLLOWING, speeds);
            return;
        }
        HumanoidMotion.apply(mob, mind, directive, selectedSpeeds);
        if (directive.movement() == HumanoidMotion.Movement.FOLLOW && meleeGuard) {
            mob.getNavigation().setSpeedModifier(selectedSpeeds.approach());
        }
    }

    private static boolean holdsForStrike(Mob mob, HumanoidMind mind) {
        // 近身守卫可在起手时跟进；出手段仍站定，收式照常移动。全程服从目录移动倍率。
        boolean closeStrike = HumanoidTechniqueHost.castingCloseStrike(mob);
        if (closeStrike && HumanoidMeleeGuard.followsWarmup(mob, mind)) {
            return false;
        }
        return closeStrike && HumanoidTechniqueHost.beforeRecovery(mob);
    }

    static Optional<LivingEntity> opponent(Mob mob) {
        // 当前对手；未被接管或无威胁时为空。
        return Optional.ofNullable(minds.get(mob.getUUID())).flatMap(HumanoidMind::opponent);
    }

    static boolean hasOpponent(Mob mob) {
        // 跃步指挥与原版撤退判定共用。
        return opponent(mob).isPresent();
    }

    public static void targetChanged(Mob mob, LivingEntity previous, LivingEntity next) {
        // 原版改目标时同步敌意反查表、作废蓄力计划；大脑还没有对手时，原版刚认出的目标下一个 tick 就交给大脑决策。
        HumanoidThreats.targeted(mob, previous, next);
        HumanoidMind mind = minds.get(mob.getUUID());
        if (previous == next || mind == null) {
            return;
        }
        mind.clearCastPlan();
        if (next != null && mind.opponent().isEmpty()) {
            mind.contact();
        }
    }

    public static boolean blocksMelee(Mob mob) {
        // 施放中不叠原版近战；撤退只有实际逃离时屏蔽攻击，无路自卫交还原版后允许出手。
        if (HumanoidRestrictions.locked(mob)) {
            return true;
        }
        HumanoidMind mind = minds.get(mob.getUUID());
        if (mind == null) {
            return HumanoidTechniqueHost.isCasting(mob);
        }
        return HumanoidTechniqueHost.isCasting(mob)
                || mind.stance() == HumanoidAssessment.Stance.RETREAT && mind.opponent().isPresent()
                && mind.directive() != HumanoidMotion.Directive.RELEASED;
    }

    public static boolean blocksRanged(Mob mob) {
        // 原版弓弩拉弦和射击不能与行矢效果重复；结束、打断和卸载后自然恢复。
        return HumanoidRestrictions.locked(mob) || HumanoidTechniqueHost.castingGuard(mob)
                || HumanoidTechniqueHost.castingWayfarer(mob)
                || mob instanceof Witch && HumanoidTechniqueHost.hasActiveCast(mob);
    }

    static List<HumanoidMind.Logged> decisions(UUID entity) {
        // 最近的决策记录，GameTest 与排查用。
        return Optional.ofNullable(minds.get(entity)).map(HumanoidMind::log).orElse(List.of());
    }

    static HumanoidArmGuard guard(Mob mob) {
        return minds.get(mob.getUUID()).guard;
    }

    public static void finishGuardTurn(Mob mob) {
        var mind = minds.get(mob.getUUID());
        if (mind != null && managed(mob)) {
            mind.guard.face(mob, config.threat().guard());
        }
    }

    static Optional<HumanoidMotion.Directive> directive(UUID entity) {
        // 当前每 tick 执行的移动指令；施放中的决策沿用它，GameTest 与排查用。
        return Optional.ofNullable(minds.get(entity)).map(HumanoidMind::directive);
    }

    static Optional<HumanoidAssessment.Stance> stance(UUID entity) {
        // 上次决策的姿态，GameTest 与排查用。
        return Optional.ofNullable(minds.get(entity)).map(HumanoidMind::stance);
    }

    static String lastFailure(UUID entity) {
        // 最近一次施放失败的式和原因，没失败过是空串；GameTest 与排查用。
        return Optional.ofNullable(minds.get(entity)).map(HumanoidMind::lastFailure).orElse("");
    }

    static int failureStreak(UUID entity) {
        // 同一式当前连续施放失败的最多次数，GameTest 与排查用。
        return Optional.ofNullable(minds.get(entity)).map(HumanoidMind::failureStreak).orElse(0);
    }

    static void forget(UUID entity) {
        // 实体离开世界后不残留大脑状态。
        minds.remove(entity);
        observed.remove(entity);
    }

    static void releaseControl(UUID entity) {
        // 切换指挥来源时撤旧目标和倒排计划，保留已经安装的 Goal，避免重复安装。
        HumanoidMind mind = minds.get(entity);
        if (mind != null) {
            mind.guard.releaseControl(entity);
            mind.disengage();
            mind.clearCastPlan();
            mind.release();
        }
    }

    static void clear() {
        // 服务器停止时一并释放。
        minds.clear();
        observed.clear();
        observedTick = Long.MIN_VALUE;
        HumanoidThreats.clear();
    }
}
