package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Encounter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTactics;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 以匀速后撤目标核对跃步的目标位置预报、安全校验和持械突击实测。 */
public final class HumanoidLeapEstimateGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_test");
    private static final String FIELD = "meridian_humanoid_test:retreat";
    private static final String ENVIRONMENT = "meridian_humanoid_test:leap_estimate_samples";
    private static final String DISTANCE_ENVIRONMENT = "meridian_humanoid_test:leap_estimate_distances";
    private static final String NINE_BLOCKS_ENVIRONMENT = "meridian_humanoid_test:leap_estimate_nine_blocks";
    private static final String MARGIN_ENVIRONMENT = "meridian_humanoid_test:leap_estimate_margin";
    private static final String KNOCK = "meridian_door_breaker:knock";
    private static final String LIGHT_STEP = "meridian_basic_techniques:light_step";
    private static final BlockPos SUBJECT = new BlockPos(20, 0, 10);
    private static final BlockPos TARGET = new BlockPos(20, 0, 16);
    private static final long BODY_SEED = 17;
    private static final long MILLIS_PER_TICK = 50;
    private static final int MEASUREMENT_TICKS = 10;
    private static final int PREPARE_TICKS = 20;
    private static final int OBSERVE_TICKS = 40;
    private static final int SAMPLE_COUNT = 5;
    private static final int SLOW_SAMPLE_ID = SAMPLE_COUNT;
    private static final int FAST_SAMPLE_ID = SAMPLE_COUNT + 1;
    private static final int EARLY_SAMPLE_ID = SAMPLE_COUNT + 2;
    private static final int FAST_SAMPLE_INDEX = SAMPLE_COUNT - 1;
    private static final int MODERATE_SAMPLE_INDEX = 2;
    private static final int DEFAULT_SAMPLE_DISTANCE = 6;
    private static final int LONG_SAMPLE_DISTANCE = 9;
    private static final int MEASUREMENT_TIMEOUT = 100;
    private static final int TIMEOUT_MARGIN_TICKS = 20;
    private static final int SAMPLE_TIMEOUT = SAMPLE_COUNT * (PREPARE_TICKS + OBSERVE_TICKS) + TIMEOUT_MARGIN_TICKS;
    private static final int MINIMUM_SAMPLE_DISTANCE = 3;
    private static final int MAXIMUM_SAMPLE_DISTANCE = 9;
    private static final int DISTANCE_SPEED_COUNT = 4;
    private static final int DISTANCE_SAMPLE_COUNT = MAXIMUM_SAMPLE_DISTANCE - MINIMUM_SAMPLE_DISTANCE + 1;
    private static final int DISTANCE_TIMEOUT = DISTANCE_SAMPLE_COUNT * DISTANCE_SPEED_COUNT
            * (PREPARE_TICKS + OBSERVE_TICKS + 1) + TIMEOUT_MARGIN_TICKS;
    private static final double RETREAT_PER_TICK = 0.2;
    private static final double SAMPLE_SPEED_STEP = 0.05;
    private static final double TARGET_HEALTH = 1000;
    private static final double STAND_OFF = 1.5;
    private static final double STRIKE_REACH = 2;
    private static final long FLIGHT_MILLIS = 500;
    private static final long FIRST_HIT_MILLIS = 450;
    private static final long AFTER_ALL_HITS_MILLIS = 1500;
    private static final double TOLERANCE = 1e-6;
    private static final Map<LivingEntity, Sample> WATCHED = new IdentityHashMap<>();

    /** 每局独立的实体身份、目标速度档位与初始距离。 */
    private record SampleSetup(int identity, int speedIndex, int distance) { }

    /** 真实伤害发生时的击次与相对起手时刻；击次来自正式目录的兵器打击时刻。 */
    private record Hit(int strike, long elapsedMillis) { }

    static {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Sample sample = WATCHED.get(entity);
            if (sample != null && source.getEntity() == sample.mob && taken > 0) {
                sample.recordHit();
            }
        });
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD)
    public void retreatPredictionMatchesMeasuredEntityMovement(GameTestHelper context) {
        Cow target = target(context);
        Vec3 start = target.position();
        Vec3 velocityPerTick = new Vec3(0, 0, RETREAT_PER_TICK);
        Vec3 predicted = HumanoidActions.predictedTargetPosition(start, velocityPerTick,
                MEASUREMENT_TICKS * MILLIS_PER_TICK);
        measureRetreat(context, target, velocityPerTick, MEASUREMENT_TICKS, () -> {
            Vec3 actual = target.position();
            double displacement = actual.distanceTo(start);
            double error = predicted.distanceTo(actual);
            LOGGER.info("leap-estimate measured_displacement={} predicted_displacement={} error={}",
                    displacement, predicted.distanceTo(start), error);
            target.discard();
            context.assertTrue(Math.abs(displacement - RETREAT_PER_TICK * MEASUREMENT_TICKS) < TOLERANCE,
                    "目标没有按每 tick 速度匀速后撤：" + displacement);
            context.assertTrue(error < TOLERANCE, "匀速后撤位置预报偏差：" + error);
            context.succeed();
        });
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD)
    public void retreatBeyondStrikeReachIsRejected(GameTestHelper context) {
        Sample sample = new Sample(context, 0);
        var plan = plan(sample, FLIGHT_MILLIS);
        try {
            HumanoidActions.Turn turn = sample.turn();
            context.assertTrue(HumanoidActions.plannedLeapSafe(turn, plan), "静止目标被错误拒绝");
            sample.target.setDeltaMovement(0, 0, RETREAT_PER_TICK);
            context.assertTrue(!HumanoidActions.plannedLeapSafe(turn, plan), "后撤出打击范围的目标仍被认为安全");
        } finally {
            sample.clean();
        }
        context.succeed();
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD)
    public void allHitsBeforeLandingIsRejected(GameTestHelper context) {
        Sample sample = new Sample(context, EARLY_SAMPLE_ID);
        try {
            var plan = plan(sample, AFTER_ALL_HITS_MILLIS);
            context.assertTrue(!HumanoidActions.plannedLeapSafe(sample.turn(), plan),
                    "所有击都早于落地时仍放行突击");
        } finally {
            sample.clean();
        }
        context.succeed();
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD, environment = ENVIRONMENT)
    public void slowRetreatIsAcceptedAndHit(GameTestHelper context) {
        var setup = new SampleSetup(SLOW_SAMPLE_ID, 1, DEFAULT_SAMPLE_DISTANCE);
        runSample(context, setup, sample -> {
            context.assertTrue(sample.leaped && sample.cast, "慢速后撤应当接受并在起跳同 tick 起手");
            context.assertTrue(sample.damage > 0, "慢速后撤突击发起后打空");
            context.succeed();
        });
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD, environment = ENVIRONMENT)
    public void fastRetreatIsRejected(GameTestHelper context) {
        var setup = new SampleSetup(FAST_SAMPLE_ID, FAST_SAMPLE_INDEX, DEFAULT_SAMPLE_DISTANCE);
        runSample(context, setup, sample -> {
            context.assertTrue(!sample.leaped && !sample.cast, "快速后撤目标仍发起突击");
            context.succeed();
        });
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD, environment = MARGIN_ENVIRONMENT)
    public void moderateRetreatSixBlocksIsAcceptedAndHit(GameTestHelper context) {
        var setup = new SampleSetup(MODERATE_SAMPLE_INDEX, MODERATE_SAMPLE_INDEX, DEFAULT_SAMPLE_DISTANCE);
        runSample(context, setup, sample -> {
            context.assertTrue(sample.leaped && sample.cast, "后撤 0.10 的六格目标应当起跳并在同 tick 起手");
            context.assertTrue(sample.damage > 0, "后撤 0.10 的六格目标突击发起后打空");
            context.succeed();
        });
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD, environment = NINE_BLOCKS_ENVIRONMENT)
    public void stationaryNineBlocksIsAcceptedAndHit(GameTestHelper context) {
        runSample(context, new SampleSetup(0, 0, LONG_SAMPLE_DISTANCE), sample -> {
            context.assertTrue(sample.leaped && sample.cast, "静止九格应当起跳并在同 tick 起手");
            context.assertTrue(sample.damage > 0, "静止九格突击发起后打空");
            context.succeed();
        });
    }

    @GameTest(maxTicks = MEASUREMENT_TIMEOUT, structure = FIELD, environment = NINE_BLOCKS_ENVIRONMENT)
    public void slowRetreatNineBlocksIsAcceptedAndHit(GameTestHelper context) {
        runSample(context, new SampleSetup(1, 1, LONG_SAMPLE_DISTANCE), sample -> {
            context.assertTrue(sample.leaped && sample.cast, "慢速后撤九格应当起跳并在同 tick 起手");
            context.assertTrue(sample.damage > 0, "慢速后撤九格突击发起后打空");
            context.succeed();
        });
    }

    @GameTest(maxTicks = SAMPLE_TIMEOUT, structure = FIELD, environment = ENVIRONMENT)
    public void vindicatorAssaultSamples(GameTestHelper context) {
        sample(context, 0);
    }

    @GameTest(maxTicks = DISTANCE_TIMEOUT, structure = FIELD, environment = DISTANCE_ENVIRONMENT)
    public void vindicatorAssaultDistanceSamples(GameTestHelper context) {
        distanceSample(context, MINIMUM_SAMPLE_DISTANCE, 0, new ArrayList<>());
    }

    private static void distanceSample(GameTestHelper context, int distance, int speedIndex, List<String> misses) {
        var setup = new SampleSetup(speedIndex, speedIndex, distance);
        runSample(context, setup, sample -> {
            sample.logResult("leap-distance", distance, speedIndex);
            if (distance == MINIMUM_SAMPLE_DISTANCE) {
                context.assertTrue(!sample.leaped && !sample.cast, "低于跃步下限的三格目标仍发起突击");
            }
            if (sample.leaped && (!sample.cast || sample.damage <= 0)) {
                misses.add("distance=" + distance + " speed_index=" + speedIndex);
            }
            int nextDistance = distance + 1;
            if (nextDistance <= MAXIMUM_SAMPLE_DISTANCE) {
                context.runAfterDelay(1, () -> distanceSample(context, nextDistance, speedIndex, misses));
            } else if (speedIndex + 1 < DISTANCE_SPEED_COUNT) {
                context.runAfterDelay(1,
                        () -> distanceSample(context, MINIMUM_SAMPLE_DISTANCE, speedIndex + 1, misses));
            } else {
                context.assertTrue(misses.isEmpty(), "突击发起后打空，见逐 tick 位置日志：" + misses);
                context.succeed();
            }
        });
    }

    private static void sample(GameTestHelper context, int index) {
        var setup = new SampleSetup(index, index, DEFAULT_SAMPLE_DISTANCE);
        runSample(context, setup, result -> {
            result.logResult("leap-five", DEFAULT_SAMPLE_DISTANCE, index);
            assertNoMiss(context, result);
            if (index == 0) {
                context.assertTrue(result.leaped && result.cast && result.damage > 0,
                        "静止对照没有起跳、起手或命中");
            }
            int next = index + 1;
            if (next == SAMPLE_COUNT) {
                context.succeed();
            } else {
                sample(context, next);
            }
        });
    }

    private static void runSample(GameTestHelper context, SampleSetup setup, Consumer<Sample> finished) {
        Sample sample = new Sample(context, setup.identity());
        Vec3 position = sample.mob.position().add(0, 0, setup.distance());
        sample.target.snapTo(position.x, position.y, position.z, 0, 0);
        sample.prepare();
        context.runAfterDelay(PREPARE_TICKS, () -> sample.assault(setup.speedIndex(), () -> finished.accept(sample)));
    }

    private static void assertNoMiss(GameTestHelper context, Sample sample) {
        context.assertTrue(!sample.leaped || sample.cast && sample.damage > 0, "突击发起后打空，见逐 tick 位置日志");
    }

    private static HumanoidActions.LeapPlan plan(Sample sample, long flightMillis) {
        Vec3 landing = sample.target.position().add(0, 0, -STAND_OFF);
        var preview = new TechniqueLeaps.Preview(landing, flightMillis);
        var strike = new Repertoire.Strike(KNOCK, FIRST_HIT_MILLIS, STRIKE_REACH, 0);
        return new HumanoidActions.LeapPlan(strike, preview, landing, flightMillis, FIRST_HIT_MILLIS);
    }

    private static void measureRetreat(GameTestHelper context, Cow target, Vec3 velocityPerTick,
                                       int ticks, Runnable finished) {
        if (ticks == 0) {
            finished.run();
            return;
        }
        context.runAfterDelay(1, () -> {
            target.move(MoverType.SELF, velocityPerTick);
            target.setDeltaMovement(Vec3.ZERO);
            measureRetreat(context, target, velocityPerTick, ticks - 1, finished);
        });
    }

    private static Cow target(GameTestHelper context) {
        Cow cow = context.spawn(EntityTypes.COW, TARGET);
        cow.setNoAi(true /* aiDisabled */);
        cow.setNoGravity(true /* noGravity */);
        cow.setSilent(true /* silent */);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        cow.setHealth((float) TARGET_HEALTH);
        return cow;
    }

    private static Vindicator vindicator(GameTestHelper context, int index) {
        var level = context.getLevel();
        Vindicator mob = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
        UUID id = UUID.nameUUIDFromBytes(("leap-estimate/" + index).getBytes(StandardCharsets.UTF_8));
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(SUBJECT));
        mob.setUUID(id);
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setOnGround(true /* onGround */);
        mob.setPersistenceRequired();
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        context.assertTrue(level.addFreshEntity(mob), "卫道士未加入世界");
        bind(mob);
        HumanoidTechniqueHost.prepare(mob);
        TechniqueFabric.service().learn(id, "meridian_basic_techniques:light_step_volume");
        TechniqueFabric.service().learn(id, "meridian_door_breaker:upper");
        return mob;
    }

    private static void bind(Vindicator mob) {
        var region = HumanoidFabric.region(mob);
        var now = HumanoidFabric.now();
        UUID id = mob.getUUID();
        var request = new HumanoidBodyGenerator.GenerationRequest(HumanoidSpecies.VINDICATOR,
                HumanoidVariant.TRUE_QI, id, region.inventory(), now);
        var snapshot = HumanoidFabric.generator().generate(request, BODY_SEED);
        var saved = new HumanoidBodyState.SavedBody(id, snapshot.toByteArray(), new byte[0][],
                region.sourceId());
        var body = HumanoidBodyState.restore(id, saved, now, HumanoidSpecies.VINDICATOR);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(body);
    }

    /** 关闭额外决策与导航，只执行一次正式持械突击，目标每 tick 经真实碰撞移动。 */
    private static final class Sample {
        private final GameTestHelper context;
        private final Vindicator mob;
        private final Cow target;
        private final List<String> positions = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private List<Long> strikeMillis = List.of();
        private boolean leaped;
        private boolean cast;
        private double damage;
        private long flightMillis;
        private long firstHitMillis;
        private long castStartedMillis;
        private int landingTick;
        private double landingDistance = Double.NaN;

        private Sample(GameTestHelper context, int index) {
            this.context = context;
            this.mob = vindicator(context, index);
            this.target = target(context);
            WATCHED.put(target, this);
        }

        private void prepare() {
            var technique = TechniqueReference.newBuilder()
                    .setId(LIGHT_STEP)
                    .setVersion(1)
                    .build();
            var request = CastRequest.newBuilder()
                    .setBodyId(HumanoidTechniqueHost.prepare(mob).body().snapshot().getId())
                    .setTechnique(technique)
                    .setCastId(UUID.randomUUID().toString())
                    .build();
            var outcome = TechniqueFabric.service().cast(mob.getUUID(), request);
            context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "轻步起手失败：" + outcome);
        }

        private HumanoidActions.Turn turn() {
            var body = HumanoidTechniqueHost.prepare(mob).body().currentBody();
            var opponent = Optional.of(new HumanoidOpponent(target, Optional.empty()));
            var full = HumanoidRepertoires.read(mob, body, opponent, technique -> false);
            var available = full.repertoire();
            var strikes = available.strikes().stream()
                    .filter(strike -> strike.technique().equals(KNOCK))
                    .toList();
            var repertoire = new Repertoire(strikes, List.of(), available.timing());
            var knownStrikes = full.strikes().stream()
                    .filter(known -> known.getTechnique().getId().equals(KNOCK))
                    .toList();
            var readout = new HumanoidRepertoires.Readout(repertoire, full.means(), full.context(), knownStrikes,
                    full.known(), full.exchanges(), full.volleyReach());
            return new HumanoidActions.Turn(mob, new HumanoidMind(), body, readout, opponent);
        }

        private void assault(int index, Runnable finished) {
            Vec3 landing = HumanoidActions.assaultLanding(mob, target);
            var preview = TechniqueLeaps.preview(mob, landing);
            HumanoidActions.Turn turn = turn();
            context.assertTrue(preview.isPresent(), "轻步后不能预报跃步");
            context.assertTrue(!turn.readout().strikes().isEmpty(), "叩门没有通过正式施放门槛");
            TechniqueLeaps.Preview leapPreview = preview.orElseThrow();
            flightMillis = leapPreview.flightMillis();
            firstHitMillis = turn.readout().repertoire().strikes().getFirst().firstHitMillis();
            double speedPerTick = index * SAMPLE_SPEED_STEP;
            Vec3 velocityPerTick = new Vec3(0, 0, speedPerTick);
            target.setDeltaMovement(velocityPerTick);
            logForecast(index, turn, leapPreview, landing);
            HumanoidActions.execute(turn, assaultDecision(turn));
            recordStart(turn);
            LOGGER.info("leap-sample start index={} speed_per_tick={} flight_ms={} leaped={} cast={}",
                    index, speedPerTick, leapPreview.flightMillis(), leaped, cast);
            target.setDeltaMovement(Vec3.ZERO);
            observe(index, velocityPerTick, OBSERVE_TICKS, finished);
        }

        private Decision assaultDecision(HumanoidActions.Turn turn) {
            var readout = turn.readout();
            var repertoire = readout.repertoire();
            var self = HumanoidFighters.self(mob, turn.body(), readout.means());
            var other = HumanoidFighters.opponent(target, Optional.empty(), readout.context().registry());
            double distance = HumanoidMotion.distance(mob, target);
            var encounter = new Encounter(self, other, distance, Optional.empty(),
                    Set.of(Encounter.Circumstance.ON_GROUND));
            Decision decision = HumanoidTactics.decide(encounter, HumanoidAssessment.Stance.ENGAGE,
                    repertoire, HumanoidBrain.config().response().tactics());
            if (distance >= repertoire.timing().leapMinimumDistance()) {
                context.assertTrue(decision.action() == Decision.Action.ASSAULT, "正式战术没有选择持械突击");
            }
            return decision;
        }

        private void recordStart(HumanoidActions.Turn turn) {
            leaped = ((TechniqueLeapAccess) mob).meridian$getLeap() != null;
            var progress = TechniqueState.read(HumanoidTechniqueHost.prepare(mob).body().currentBody());
            var activeCast = progress.getActiveCast();
            cast = progress.hasActiveCast() && activeCast.getRequest().getTechnique().getId().equals(KNOCK);
            if (cast) {
                castStartedMillis = activeCast.getStartedMillis();
                long leapStartMillis = turn.body().settledAt().elapsedMillis();
                context.assertTrue(castStartedMillis == leapStartMillis, "突击起跳和起手不在同一 tick");
                var definition = turn.readout().context().registry()
                        .find(activeCast.getRequest().getTechnique()).orElseThrow();
                strikeMillis = definition.getEffectsList().stream()
                        .filter(TechniqueEffect::hasWeaponStrike)
                        .map(TechniqueEffect::getAtMillis)
                        .toList();
            }
        }

        private void recordHit() {
            long elapsedMillis = HumanoidFabric.now().getElapsedMillis() - castStartedMillis;
            int strike = strikeMillis.indexOf(elapsedMillis) + 1;
            hits.add(new Hit(strike, elapsedMillis));
            LOGGER.info("leap-hit strike={} elapsed_ms={} hp={}", strike, elapsedMillis, target.getHealth());
        }

        private void logForecast(int index, HumanoidActions.Turn turn, TechniqueLeaps.Preview preview, Vec3 landing) {
            var strike = turn.readout().repertoire().strikes().getFirst();
            Vec3 position = target.position();
            Vec3 velocityPerTick = target.getDeltaMovement();
            long flightMillis = preview.flightMillis();
            long firstHitMillis = strike.firstHitMillis();
            Vec3 flightTarget = HumanoidActions.predictedTargetPosition(position, velocityPerTick, flightMillis);
            Vec3 hitTarget = HumanoidActions.predictedTargetAtLandingOrFirstHit(position, velocityPerTick,
                    flightMillis, firstHitMillis);
            LOGGER.info("leap-sample forecast index={} first_hit_ms={} reach={} flight_distance={} safety_distance={}",
                    index, firstHitMillis, strike.reach(), landing.distanceTo(flightTarget),
                    preview.landing().distanceTo(hitTarget));
        }

        private void observe(int index, Vec3 velocityPerTick, int ticks, Runnable finished) {
            if (leaped) {
                positions.add("tick=" + (OBSERVE_TICKS - ticks) + " mob=" + mob.position()
                        + " target=" + target.position() + " hp=" + target.getHealth());
            }
            if (ticks == 0) {
                finish(index, finished);
                return;
            }
            context.runAfterDelay(1, () -> {
                // NoAI 不自动推进飞行；每个世界 tick 调用一次正式宿主，保留碰撞和落地结算。
                TechniqueLeaps.travel(mob);
                target.move(MoverType.SELF, velocityPerTick);
                target.setDeltaMovement(Vec3.ZERO);
                recordLanding(OBSERVE_TICKS - ticks + 1);
                observe(index, velocityPerTick, ticks - 1, finished);
            });
        }

        private void recordLanding(int tick) {
            if (!leaped || landingTick > 0 || ((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
                return;
            }
            context.assertTrue(mob.onGround(), "飞行结束时没有实际落地");
            landingTick = tick;
            double dx = mob.getX() - target.getX();
            double dz = mob.getZ() - target.getZ();
            landingDistance = Math.hypot(dx, dz);
            LOGGER.info("leap-landing tick={} distance={} mob={} target={}",
                    tick, landingDistance, mob.position(), target.position());
        }

        private void finish(int index, Runnable finished) {
            damage = TARGET_HEALTH - target.getHealth();
            double distance = mob.position().distanceTo(target.position());
            LOGGER.info("leap-sample result index={} leaped={} cast={} hit={} damage={} final_distance={}",
                    index, leaped, cast, damage > 0, damage, distance);
            if (leaped && damage <= 0) {
                positions.forEach(position -> LOGGER.info("leap-miss-position index={} {}", index, position));
            }
            clean();
            context.assertTrue(!leaped || landingTick > 0, "突击没有记录到真实落地");
            context.assertTrue(hits.stream().allMatch(hit -> hit.strike() > 0), "伤害时刻不对应叩门击次：" + hits);
            context.assertTrue((damage > 0) == !hits.isEmpty(), "伤害和命中记录不一致：" + hits);
            finished.run();
        }

        private void logResult(String label, int distance, int speedIndex) {
            LOGGER.info("{} distance={} speed_per_tick={} leaped={} cast={} hit={}"
                            + " flight_ms={} first_hit_ms={} damage={} landing_tick={} landing_distance={} hits={}",
                    label, distance, speedIndex * SAMPLE_SPEED_STEP, leaped, cast, damage > 0,
                    flightMillis, firstHitMillis, damage, landingTick, landingDistance, hits);
        }

        private void clean() {
            WATCHED.remove(target);
            target.discard();
            mob.discard();
        }
    }
}
