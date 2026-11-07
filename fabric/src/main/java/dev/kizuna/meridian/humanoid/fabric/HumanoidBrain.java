package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Encounter;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTactics;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import dev.kizuna.meridian.technique.server.TechniqueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

/** 类人大脑：被接管实体按错相周期读敌意、评估、选战术，再由接入适配器落到原版控制上。 */
public final class HumanoidBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_brain");
    private static final long SEQUENCE_SPREAD = 0x9E3779B97F4A7C15L;
    private static final Map<UUID, HumanoidMind> minds = new HashMap<>();
    private static final Map<EntityType<?>, Boolean> managedTypes = new IdentityHashMap<>();
    private static final Map<UUID, Optional<TechniqueService.Observation>> observed = new HashMap<>();
    private static long observedTick = Long.MIN_VALUE;
    private static HumanoidBrainConfig config;

    /** 一式及其情境评分。 */
    private record Scored(TechniqueReference reference, double score) { }

    private HumanoidBrain() {
        // 状态按实体 UUID 保存，实体离开世界时清理。
    }

    static void configure(HumanoidBrainConfig next) {
        // 启动时装配；GameTest 换成只发指定卷的配置。物种可能在其后登记，接管判定缓存重算。
        config = next;
        managedTypes.clear();
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
        return managedTypes.computeIfAbsent(entity.getType(), type -> HumanoidSpecies
                .fromEntityId(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                .map(HumanoidSpecies::brainManaged)
                .orElse(false));
    }

    public static void tick(Mob mob, GoalSelector goals, GoalSelector targets) {
        // 每 tick 只装接入；到错相决策时刻才读敌意与身体，飞行中不改主意。
        if (config == null || !HumanoidFabric.isReady() || !managed(mob)) {
            return;
        }
        HumanoidMind mind = minds.computeIfAbsent(mob.getUUID(), ignored -> new HumanoidMind());
        HumanoidGoalAdapter.install(mob, mind, goals, targets);
        int interval = config.schedule().decisionIntervalTicks();
        if (((TechniqueLeapAccess) mob).meridian$getLeap() != null
                || Math.floorMod((long) mob.tickCount + mob.getId(), interval) != 0) {
            return;
        }
        try {
            decide(mob, mind);
        } catch (RuntimeException failure) {
            mind.release();
            HumanoidFabric.report(mob, failure);
        }
    }

    private static void decide(Mob mob, HumanoidMind mind) {
        // 先找威胁再读身体；自己施放中而对方没有来袭时不必准备身体。
        Optional<LivingEntity> threat = HumanoidThreats.nearest(mob, config.threat(), mind.opponent());
        if (threat.isEmpty()) {
            idle(mob, mind);
            return;
        }
        mind.engage(threat.get());
        var opponent = new HumanoidOpponent(threat.get(), observe(threat.get()));
        if (HumanoidTechniqueHost.hasActiveCast(mob) && opponent.cast().isEmpty()
                && !HumanoidTechniqueHost.castingWayfarer(mob)) {
            return;
        }
        fight(mob, mind, opponent);
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
        double distance = HumanoidMotion.distance(mob, opponent.entity());
        var assessed = HumanoidAssessment.stance(self, other, mind.stance(), distance, config.assessment());
        HumanoidAssessment.Stance stance;
        if (HumanoidRepertoires.peddlerShouldRetreat(readout)) {
            stance = HumanoidAssessment.Stance.RETREAT;
        } else {
            stance = assessed;
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
        Decision decision = HumanoidWayfarerAi.continueOrEscape(self, stance, readout, ordinary);
        record(mob, mind, stance, decision);
        HumanoidActions.execute(new HumanoidActions.Turn(mob, mind, body, readout, Optional.of(opponent)),
                decision);
    }

    private static Set<Encounter.Circumstance> combatCircumstances(Mob mob, HumanoidMind mind,
                                                                  HumanoidAssessment.Stance stance, Fighter self) {
        // 受困后只有找到通往安全距离的退路才恢复逃离，避免刚向敌人迈一步又退回同一墙角。
        var result = circumstances(mob);
        if (stance != HumanoidAssessment.Stance.RETREAT) {
            return result;
        }
        if (mob.onGround()) {
            double safeDistance = 0;
            if (mind.cornered() && self.has(Fighter.Means.VANILLA_OFFENSE)) {
                safeDistance = config.response().tactics().safeDistance();
            }
            LivingEntity opponent = mind.opponent().orElseThrow();
            mind.planRetreat(HumanoidMotion.retreatPoint(mob, opponent, mind.directive(), safeDistance));
        }
        if (mind.cornered()) {
            result.add(Encounter.Circumstance.RETREAT_BLOCKED);
        }
        return result;
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
        return BodyProtobuf.fromMessage(HumanoidTechniqueHost.prepare(mob).body().snapshot());
    }

    private static Optional<TechniqueService.Observation> observe(LivingEntity entity) {
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
        HumanoidMind mind = minds.get(mob.getUUID());
        if (mind == null || !managed(mob)) {
            return false;
        }
        return mind.directive() != HumanoidMotion.Directive.RELEASED
                || HumanoidTechniqueHost.hasActiveCast(mob) && mind.opponent().isPresent();
    }

    static void move(Mob mob, HumanoidMotion.Speeds speeds) {
        // 施放打击时面向对手缓行；其余执行当前指令。
        HumanoidMind mind = minds.get(mob.getUUID());
        if (mind == null) {
            return;
        }
        if (HumanoidTechniqueHost.castingWayfarer(mob)) {
            HumanoidMotion.apply(mob, HumanoidMotion.Directive.STAYING, mind.opponent(), speeds);
            return;
        }
        var directive = mind.directive();
        if (directive == HumanoidMotion.Directive.RELEASED) {
            HumanoidMotion.apply(mob, HumanoidMotion.Directive.FOLLOWING, mind.opponent(), speeds);
            return;
        }
        HumanoidMotion.apply(mob, directive, mind.opponent(), speeds);
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
        // 原版改目标时同步敌意反查表。
        HumanoidThreats.targeted(mob, previous, next);
    }

    public static boolean blocksMelee(Mob mob) {
        // 施放中不叠原版近战；撤退只有实际逃离时屏蔽攻击，无路自卫交还原版后允许出手。
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
        return HumanoidTechniqueHost.castingWayfarer(mob);
    }

    static List<HumanoidMind.Logged> decisions(UUID entity) {
        // 最近的决策记录，GameTest 与排查用。
        return Optional.ofNullable(minds.get(entity)).map(HumanoidMind::log).orElse(List.of());
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

    static void clear() {
        // 服务器停止时一并释放。
        minds.clear();
        observed.clear();
        observedTick = Long.MIN_VALUE;
        HumanoidThreats.clear();
    }
}
