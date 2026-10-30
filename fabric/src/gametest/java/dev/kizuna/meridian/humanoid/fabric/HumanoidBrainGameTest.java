package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiRole;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.brain.Activity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.AfterBatch;
import net.minecraft.test.BeforeBatch;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 类人大脑整体回归：撤退、对敌、准备、恢复、原版日常与两只真炁卫道士互打，均用正式目录和原版实体。 */
public final class HumanoidBrainGameTest implements FabricGameTest {
    private static final String UNARMED_BATCH = "humanoid-brain-unarmed";
    private static final String ARMED_VILLAGER_BATCH = "humanoid-brain-armed-villager";
    private static final String LIGHT_STEP_BATCH = "humanoid-brain-light-step";
    private static final String BREATH_BATCH = "humanoid-brain-breath";
    private static final String CHARGE_BATCH = "humanoid-brain-charge";
    private static final String PILLAGER_BATCH = "humanoid-brain-pillager";
    private static final String SLEEP_BATCH = "humanoid-brain-sleep";
    private static final String CROWD_BATCH = "humanoid-brain-crowd";
    private static final String DUEL_BATCH = "humanoid-brain-duel";
    private static final String EXHAUSTION_BATCH = "humanoid-brain-exhaustion";
    private static final String BASIC_PREFIX = "meridian_basic_techniques:";
    private static final String DOOR_BREAKER_PREFIX = "meridian_door_breaker:";
    private static final String CHARGE = BASIC_PREFIX + "charge";
    private static final String LIGHT_STEP = BASIC_PREFIX + "light_step";
    private static final String BREATH = BASIC_PREFIX + "breath";
    private static final String KNOCK = DOOR_BREAKER_PREFIX + "knock";
    private static final BlockPos SUBJECT_POS = new BlockPos(2, 0, 2);
    private static final BlockPos NEAR_POS = new BlockPos(2, 0, 4);
    private static final BlockPos MIDDLE_POS = new BlockPos(2, 0, 7);
    private static final BlockPos FAR_POS = new BlockPos(2, 0, 8);
    private static final BlockPos BED_FOOT_POS = new BlockPos(4, 0, 2);
    private static final int SHORT_TIMEOUT = 400;
    private static final int LONG_TIMEOUT = 800;
    private static final int RETREAT_TICKS = 200;
    private static final int RESUME_TICKS = 200;
    private static final int ENGAGE_TICKS = 600;
    private static final int LIGHT_STEP_TICKS = 300;
    private static final int EXHAUSTION_TICKS = 300;
    private static final int BREATH_TICKS = 60;
    private static final int CHARGE_TICKS = 400;
    private static final int PILLAGER_TICKS = 300;
    private static final int SLEEP_TICKS = 600;
    private static final int CROWD_SIDE = 10;
    private static final int CROWD_TICKS = 1200;
    private static final int CROWD_TIMEOUT = CROWD_TICKS + 100;
    private static final int PEN_HEIGHT = 2;
    private static final int DUEL_TICKS = 3600;
    private static final int DUEL_TIMEOUT = 4200;
    private static final int FAILURE_STREAK_LIMIT = 3;
    private static final double RETREAT_GAIN = 2;
    private static final double TARGET_HEALTH = 1000;
    private static final double FATIGUED_STRENGTH = 0.3;
    private static final long FATIGUE_MILLIS = 30_000;
    private static final long NIGHT_TIME = 13_000;
    private static final Set<String> ARM_MUSCLES = Set.of(HumanoidFighters.ARM_MUSCLE);
    private static final Set<String> LIMB_MUSCLES = Set.of(HumanoidFighters.ARM_MUSCLE, HumanoidFighters.LEG_MUSCLE);
    private static final Set<Decision.Action> RESPONSES = EnumSet.of(Decision.Action.INTERRUPT,
            Decision.Action.DODGE, Decision.Action.ABSORB);

    @BeforeBatch(batchId = UNARMED_BATCH)
    public void beforeUnarmedBatch(ServerWorld world) {
        // 村民学卷表清空，验证无功法时的撤退。
        HumanoidBrainTestConfigs.withVolumes(HumanoidSpecies.VILLAGER.entityId(), List.of());
    }

    @AfterBatch(batchId = UNARMED_BATCH)
    public void afterUnarmedBatch(ServerWorld world) {
        // 后续批次用启动配置。
        HumanoidBrainTestConfigs.restore();
    }

    @BeforeBatch(batchId = PILLAGER_BATCH)
    public void beforePillagerBatch(ServerWorld world) {
        // 掠夺者学卷表清空，验证被接管后仍用原版射击。
        HumanoidBrainTestConfigs.withVolumes(HumanoidSpecies.PILLAGER.entityId(), List.of());
    }

    @AfterBatch(batchId = PILLAGER_BATCH)
    public void afterPillagerBatch(ServerWorld world) {
        // 后续批次用启动配置。
        HumanoidBrainTestConfigs.restore();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = LONG_TIMEOUT, batchId = UNARMED_BATCH)
    public void unarmedVillagerRetreatsFromVindicatorAndResumesRoutine(TestContext context) {
        // 无功法的游炁村民被卫道士盯上时由大脑撤退而非原版恐慌；卫道士消失后交还原版日常。
        var arena = new Arena(context);
        VillagerEntity villager = arena.spawn(EntityType.VILLAGER, HumanoidTier.WANDERING_QI, "unarmed", SUBJECT_POS);
        VindicatorEntity hunter = arena.spawn(EntityType.VINDICATOR, HumanoidTier.WANDERING_QI, "unarmed-hunter",
                NEAR_POS);
        hunter.setAiDisabled(true /* aiDisabled */);
        hunter.setTarget(villager);
        double start = villager.distanceTo(hunter);
        BooleanSupplier retreated = () -> lastActionIs(villager, Decision.Action.RETREAT)
                && villager.distanceTo(hunter) > start + RETREAT_GAIN;
        arena.await(RETREAT_TICKS, retreated,
                () -> "村民没有撤退拉开：" + decisions(villager), () -> {
                    context.assertTrue(!villager.getBrain().hasActivity(Activity.PANIC), "村民进入了原版恐慌");
                    hunter.discard();
                    arena.await(RESUME_TICKS, () -> !HumanoidBrain.directing(villager) && routineRunning(villager),
                            () -> "威胁消失后村民没有回到原版日常：" + decisions(villager), arena::finish);
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = LONG_TIMEOUT,
            batchId = ARMED_VILLAGER_BATCH)
    public void trueQiVillagerWithUpperVolumeEngagesAndKnocks(TestContext context) {
        // 学了破门上卷、持斧的真炁村民对游炁卫道士对敌，施放叩门并打伤对方。
        var arena = new Arena(context);
        VillagerEntity villager = arena.spawn(EntityType.VILLAGER, HumanoidTier.TRUE_QI, "armed", SUBJECT_POS);
        villager.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        HumanoidTechniqueHost.prepare(villager);
        TechniqueFabric.service().learn(villager.getUuid(), DOOR_BREAKER_PREFIX + "upper");
        VindicatorEntity hunter = arena.spawn(EntityType.VINDICATOR, HumanoidTier.WANDERING_QI, "armed-hunter",
                NEAR_POS);
        hunter.setAiDisabled(true /* aiDisabled */);
        hunter.setTarget(villager);
        double hp = partHpRatio(hunter);
        var watch = new Watch(villager);
        arena.await(ENGAGE_TICKS, () -> watch.observe().cast(KNOCK) && partHpRatio(hunter) < hp,
                () -> "村民没有对敌叩门伤到卫道士：" + watch + " / " + decisions(villager), () -> {
                    context.assertTrue(watch.saw(Decision.Action.STRIKE) || watch.saw(Decision.Action.INTERRUPT),
                            "村民没有对敌出手：" + watch);
                    arena.finish();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TIMEOUT, batchId = LIGHT_STEP_BATCH)
    public void retreatingVillagerLightStepsAndLeapsAway(TestContext context) {
        // 只会基础三卷的游炁村民撤退时先放轻步，再背向跃步拉开。
        var arena = new Arena(context);
        VillagerEntity villager = arena.spawn(EntityType.VILLAGER, HumanoidTier.WANDERING_QI, "light-step",
                SUBJECT_POS);
        VindicatorEntity hunter = arena.spawn(EntityType.VINDICATOR, HumanoidTier.WANDERING_QI, "light-step-hunter",
                MIDDLE_POS);
        hunter.setAiDisabled(true /* aiDisabled */);
        hunter.setTarget(villager);
        var watch = new Watch(villager);
        arena.await(LIGHT_STEP_TICKS,
                () -> watch.observe().cast(LIGHT_STEP) && watch.saw(Decision.Action.KITE) && watch.leaped(),
                () -> "撤退村民没有轻步后跃步：" + watch + " / " + decisions(villager), arena::finish);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TIMEOUT, batchId = EXHAUSTION_BATCH)
    public void exhaustedVindicatorLeapsAwayInsteadOfTrading(TestContext context) {
        // 双臂虚脱的持斧真炁卫道士被盯上时不原地对打，先轻步再背向跃步拉开。
        var arena = new Arena(context);
        VindicatorEntity exhausted = arena.spawn(EntityType.VINDICATOR, HumanoidTier.TRUE_QI, "exhausted",
                SUBJECT_POS);
        exhausted.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        fatigue(exhausted, HumanoidSpecies.VINDICATOR, ARM_MUSCLES);
        VindicatorEntity hunter = arena.spawn(EntityType.VINDICATOR, HumanoidTier.WANDERING_QI, "exhausted-hunter",
                NEAR_POS);
        hunter.setAiDisabled(true /* aiDisabled */);
        hunter.setTarget(exhausted);
        double exhaustedRatio = HumanoidBrain.config().assessment().retreat().armStrengthRatio();
        var watch = new Watch(exhausted, exhaustedRatio);
        arena.await(EXHAUSTION_TICKS, () -> watch.observe().kitedAfterExhaustion() && watch.leaped(),
                () -> "虚脱卫道士没有背向跃步拉开：" + watch + " / " + decisions(exhausted), () -> {
                    context.assertTrue(watch.cast(LIGHT_STEP), "跃步前没有轻步：" + watch);
                    arena.finish();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TIMEOUT, batchId = BREATH_BATCH)
    public void idleFatiguedVillagerBreathes(TestContext context) {
        // 无威胁时四肢疲劳达到调息门槛，村民自行调息。
        var arena = new Arena(context);
        VillagerEntity villager = arena.spawn(EntityType.VILLAGER, HumanoidTier.WANDERING_QI, "breath", SUBJECT_POS);
        fatigue(villager, HumanoidSpecies.VILLAGER, LIMB_MUSCLES);
        var watch = new Watch(villager);
        arena.await(BREATH_TICKS, () -> watch.observe().cast(BREATH),
                () -> "疲劳村民无威胁时没有调息：" + watch + " / " + decisions(villager) + " / "
                        + recoveryDiagnosis(villager), () -> {
                    context.assertTrue(watch.saw(Decision.Action.RECOVER), "调息不是恢复决策：" + watch);
                    arena.finish();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TIMEOUT, batchId = CHARGE_BATCH)
    public void axeVindicatorChargesBeforeStriking(TestContext context) {
        // 持斧真炁卫道士在打击距离外先放蓄力，之后才出破门打击式。
        var arena = new Arena(context);
        VindicatorEntity vindicator = arena.spawn(EntityType.VINDICATOR, HumanoidTier.TRUE_QI, "charge",
                SUBJECT_POS);
        vindicator.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        CowEntity target = arena.target(FAR_POS);
        vindicator.setTarget(target);
        var watch = new Watch(vindicator);
        arena.await(CHARGE_TICKS, () -> watch.observe().castAny(DOOR_BREAKER_PREFIX),
                () -> "卫道士没有出破门打击式：" + watch + " / " + decisions(vindicator), () -> {
                    context.assertTrue(watch.castBefore(CHARGE, DOOR_BREAKER_PREFIX), "打击前没有蓄力：" + watch);
                    arena.finish();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = SHORT_TIMEOUT, batchId = PILLAGER_BATCH)
    public void emptyTablePillagerStillShoots(TestContext context) {
        // 空学卷表的掠夺者被接管后仍交还原版弩射击，不会呆住。
        var arena = new Arena(context);
        PillagerEntity pillager = arena.spawn(EntityType.PILLAGER, HumanoidTier.WANDERING_QI, "pillager",
                SUBJECT_POS);
        pillager.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
        CowEntity target = arena.target(FAR_POS);
        float health = target.getHealth();
        pillager.setTarget(target);
        arena.await(PILLAGER_TICKS, () -> target.getHealth() < health,
                () -> "空表掠夺者没有射中目标：" + decisions(pillager), arena::finish);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = LONG_TIMEOUT, batchId = SLEEP_BATCH)
    public void idleVillagerStillSleepsAtNight(TestContext context) {
        // 夜里无威胁的村民照原版作息上床，大脑不记决策。
        var arena = new Arena(context);
        var world = context.getWorld();
        long previousTime = world.getTimeOfDay();
        arena.onFinish(() -> world.setTimeOfDay(previousTime));
        BlockPos head = bed(context);
        VillagerEntity villager = arena.spawnUnborn(EntityType.VILLAGER, "sleep", SUBJECT_POS);
        villager.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(world.getRegistryKey(), head));
        world.setTimeOfDay(NIGHT_TIME);
        arena.await(SLEEP_TICKS, villager::isSleeping, () -> "无威胁村民夜里没有上床：" + decisions(villager), () -> {
            context.assertTrue(HumanoidBrain.decisions(villager.getUuid()).isEmpty(), "无威胁村民留下了大脑决策");
            arena.finish();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = CROWD_TIMEOUT, batchId = CROWD_BATCH)
    public void hundredIdleVillagersPrepareNoBodiesForAMinute(TestContext context) {
        // 一百个无威胁村民跑满一分钟；健康宿主照常惰性出生身体，大脑不准备身体会话，也不记决策。
        var arena = new Arena(context);
        arena.pen(CROWD_SIDE, PEN_HEIGHT);
        var villagers = new ArrayList<VillagerEntity>();
        for (int x = 0; x < CROWD_SIDE; x++) {
            for (int z = 0; z < CROWD_SIDE; z++) {
                String name = "crowd-" + x + "-" + z;
                villagers.add(arena.spawnUnborn(EntityType.VILLAGER, name, new BlockPos(x, 0, z)));
            }
        }
        arena.later(CROWD_TICKS, () -> {
            for (VillagerEntity villager : villagers) {
                String subject = villager.getUuidAsString() + " 对手=" + HumanoidBrain.opponent(villager)
                        + " 决策=" + decisions(villager);
                context.assertTrue(!HumanoidTechniqueHost.hasSession(villager),
                        "无威胁村民准备了身体会话：" + subject);
                context.assertTrue(HumanoidBrain.decisions(villager.getUuid()).isEmpty(),
                        "无威胁村民留下了大脑决策：" + subject);
            }
            arena.finish();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = DUEL_TIMEOUT, batchId = DUEL_BATCH)
    public void trueQiVindicatorsDuelForThreeMinutes(TestContext context) {
        // 两只持斧真炁卫道士互设目标打三分钟或打到一方倒下，检查应对、准备与失败上限；虚脱后拉开另有确定性用例。
        var arena = new Arena(context);
        VindicatorEntity first = arena.spawn(EntityType.VINDICATOR, HumanoidTier.TRUE_QI, "duel-first", SUBJECT_POS);
        VindicatorEntity second = arena.spawn(EntityType.VINDICATOR, HumanoidTier.TRUE_QI, "duel-second", FAR_POS);
        for (VindicatorEntity fighter : List.of(first, second)) {
            fighter.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        }
        first.setTarget(second);
        second.setTarget(first);
        var watches = List.of(new Watch(first), new Watch(second));
        arena.observe(DUEL_TICKS, () -> duelOver(watches), () -> {
            assertDuel(context, watches);
            arena.finish();
        });
    }

    private static boolean duelOver(List<Watch> watches) {
        // 每 tick 记录双方，任一方倒下即结束。
        watches.forEach(Watch::observe);
        return watches.stream().anyMatch(watch -> !watch.mob().isAlive());
    }

    private static void assertDuel(TestContext context, List<Watch> watches) {
        // 至少一方起手过打击式，对手起手过的一方应对过来袭；双方都蓄过力，同一式连续失败不到三次。
        context.assertTrue(watches.stream().anyMatch(watch -> watch.castAny(DOOR_BREAKER_PREFIX)),
                "双方都没有起手打击式：" + watches);
        for (Watch watch : watches) {
            if (opponentStruck(watch, watches)) {
                String duel = watches + " / " + decisions(watch.mob());
                context.assertTrue(watch.sawAny(RESPONSES), "对手起手过打击式，这一方却没有打断、躲或硬抗：" + duel);
            }
            context.assertTrue(watch.cast(CHARGE), "一方开打前没有蓄力：" + watch);
            context.assertTrue(watch.failureStreak() < FAILURE_STREAK_LIMIT, "同一式连续失败三次以上：" + watch);
        }
    }

    private static boolean opponentStruck(Watch watch, List<Watch> watches) {
        // 另一方施放过破门目录的打击式；一方整场撤退时对面没有可应对的起手。
        return watches.stream()
                .filter(other -> other != watch)
                .anyMatch(other -> other.castAny(DOOR_BREAKER_PREFIX));
    }

    private static boolean lastActionIs(MobEntity mob, Decision.Action action) {
        // 最近一条决策是否为该动作；没有决策时为否。
        var log = HumanoidBrain.decisions(mob.getUuid());
        return !log.isEmpty() && log.get(log.size() - 1).decision().action() == action;
    }

    private static String decisions(MobEntity mob) {
        // 失败信息里列出最近的决策与理由。
        return HumanoidBrain.decisions(mob.getUuid()).stream()
                .map(logged -> logged.tick() + ":" + logged.decision().action()
                        + "(" + logged.decision().reason() + ")")
                .toList()
                .toString();
    }

    private static boolean routineRunning(VillagerEntity villager) {
        // 原版大脑重新跑任务，或已有日常走路目标。
        var brain = villager.getBrain();
        return !brain.getRunningTasks().isEmpty() || brain.hasMemoryModule(MemoryModuleType.WALK_TARGET);
    }

    private static double partHpRatio(MobEntity mob) {
        // 权威身体的完好率，不结清时间。
        var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        return BodyProtobuf.fromMessage(body.snapshot()).partHpRatio();
    }

    private static List<String> castTechniques(MobEntity mob) {
        // 权威快照里上一次与正在施放的式，先旧后新；没有身体时为空。
        var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        if (body == null) {
            return List.of();
        }
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(body.snapshot()));
        var result = new ArrayList<String>();
        if (progress.hasLastCast()) {
            result.add(progress.getLastCast().getTechnique().getId());
        }
        if (progress.hasActiveCast()) {
            result.add(progress.getActiveCast().getRequest().getTechnique().getId());
        }
        return result;
    }

    private static BlockPos bed(TestContext context) {
        // 在村民旁放一张朝东的床，返回床头的世界坐标。
        var state = Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.EAST);
        BlockPos headPos = BED_FOOT_POS.east();
        context.setBlockState(BED_FOOT_POS, state.with(BedBlock.PART, BedPart.FOOT));
        context.setBlockState(headPos, state.with(BedBlock.PART, BedPart.HEAD));
        return context.getAbsolutePos(headPos);
    }

    private static void fatigue(MobEntity mob, HumanoidSpecies species, Set<String> muscles) {
        // 先准备会话学卷，再把带疲劳的快照恢复成唯一 Store 并重新准备会话。
        HumanoidTechniqueHost.prepare(mob);
        var fatigued = fatigued(((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot(), muscles);
        HumanoidTechniqueHost.removed(mob);
        bind(mob, species, fatigued);
        HumanoidTechniqueHost.prepare(mob);
    }

    private static String recoveryDiagnosis(MobEntity mob) {
        // 调息失败时区分没有会话、没学会、绑不上、付不起与门槛不够。
        if (!HumanoidTechniqueHost.hasSession(mob)) {
            return "没有身体会话";
        }
        var body = BodyProtobuf.fromMessage(((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot());
        var readout = HumanoidRepertoires.read(mob, body, Optional.empty(), technique -> false);
        var known = readout.known().stream().map(entry -> entry.getTechnique().getId()).toList();
        var registry = TechniqueFabric.service().registry();
        String binding = readout.known().stream()
                .filter(entry -> entry.getTechnique().getId().equals(BREATH))
                .flatMap(entry -> registry.find(entry.getTechnique()).stream())
                .map(definition -> bindingSummary(body, definition))
                .findFirst()
                .orElse("未学调息");
        return "会恢复式=" + HumanoidTechniqueHost.knowsRole(mob, AiRole.AI_ROLE_RECOVER) + " 已学=" + known
                + " 调息绑定=" + binding + " 辅助式=" + readout.repertoire().supports()
                + " 状态=" + HumanoidFighters.condition(body);
    }

    private static String bindingSummary(BodySnapshot body, TechniqueDefinition definition) {
        // 绑定失败给原因码与说明，通过时再看费用。
        BindingOutcome outcome = TechniqueBinder.bind(body, definition);
        if (!outcome.hasBinding()) {
            return outcome.getFailure() + "(" + outcome.getReason() + ")";
        }
        return "通过 付得起=" + TechniqueAvailability.affordable(body, definition, outcome.getBinding());
    }

    private static BodyMessages.BodySnapshot fatigued(BodyMessages.BodySnapshot body, Set<String> muscles) {
        // 指定标签的肌肉施加一段恒定的力量下降，疲劳比高于调息门槛，较强一臂也低于虚脱门槛。
        var result = body.toBuilder();
        var start = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(0)
                .setValue(1)
                .build();
        var end = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(FATIGUE_MILLIS)
                .setValue(1)
                .build();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (tissue.getTagsList().stream().noneMatch(muscles::contains)) {
                continue;
            }
            var modifier = BodyMessages.CapabilityModifier.newBuilder()
                    .setId("gametest:fatigue/" + tissue.getId().replace(':', '/'))
                    .setSource("gametest:fatigue")
                    .setTissueId(tissue.getId())
                    .setStartMillis(body.getSettledAt().getElapsedMillis())
                    .setStrengthPeak(FATIGUED_STRENGTH)
                    .setBurstPeak(FATIGUED_STRENGTH)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .addCurve(start)
                    .addCurve(end)
                    .build();
            result.addModifiers(modifier);
        }
        return result.build();
    }

    private static void bind(MobEntity mob, HumanoidSpecies species, BodyMessages.BodySnapshot snapshot) {
        // 从合法快照恢复唯一 Store，测试不直接改运行中的权威状态。
        var saved = new HumanoidBodyState.SavedBody(mob.getUuid(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        var body = HumanoidBodyState.restore(mob.getUuid(), saved, HumanoidFabric.now(), species);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(body);
    }

    /** 一个用例的场地：记下生成的实体，结束或失败时一并移除，免得带 AI 的实体留到后续批次。 */
    private static final class Arena {
        private final TestContext context;
        private final List<Entity> spawned = new ArrayList<>();
        private final List<Runnable> cleanups = new ArrayList<>();

        private Arena(TestContext context) {
            // 场地只属于一个用例；每个用例独占一个批次，开场先清掉半径内前面批次留下的生物。
            this.context = context;
            clearMobs(HumanoidBrain.config().threat().forgetDistance() + CROWD_SIDE);
        }

        <T extends MobEntity> T spawn(EntityType<T> type, HumanoidTier tier, String name, BlockPos relative) {
            // 按指定档位出生身体后再加入世界。
            T mob = placed(type, name, relative);
            HumanoidSpeciesBirths.birth(mob, tier);
            return joined(mob);
        }

        <T extends MobEntity> T spawnUnborn(EntityType<T> type, String name, BlockPos relative) {
            // 不出生身体，留给宿主惰性出生。
            return joined(placed(type, name, relative));
        }

        private <T extends MobEntity> T placed(EntityType<T> type, String name, BlockPos relative) {
            // 固定 UUID 让选式种子可重复；GameTest 世界跨运行保留，上次超时留下的同 UUID 实体先移除。
            UUID id = UUID.nameUUIDFromBytes(("humanoid-brain/" + name).getBytes(StandardCharsets.UTF_8));
            Entity stale = context.getWorld().getEntity(id);
            if (stale != null) {
                stale.discard();
            }
            T mob = type.create(context.getWorld());
            Vec3d position = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
            mob.setUuid(id);
            mob.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
            mob.setPersistent();
            return mob;
        }

        private <T extends MobEntity> T joined(T mob) {
            // 加入世界并登记清场。
            context.assertTrue(context.getWorld().spawnEntity(mob), "实体未加入世界：" + mob.getUuidAsString());
            spawned.add(mob);
            return mob;
        }

        private void clearMobs(double radius) {
            // 留在场地外的生物会被原版敌对感知读到而成为敌意来源；半径覆盖放下距离与一百村民的方阵。
            Box area = new Box(context.getAbsolutePos(BlockPos.ORIGIN)).expand(radius);
            context.getWorld().getEntitiesByClass(MobEntity.class, area, mob -> true).forEach(Entity::discard);
        }

        void pen(int side, int height) {
            // 闲逛一分钟会走出清场半径，撞上前面批次留在原地的生物；屏障把方阵关在清过的范围内，结束时拆除。
            var world = context.getWorld();
            Set<BlockPos> posts = new LinkedHashSet<>();
            for (int y = 0; y < height; y++) {
                for (int offset = -1; offset <= side; offset++) {
                    posts.add(context.getAbsolutePos(new BlockPos(offset, y, -1)));
                    posts.add(context.getAbsolutePos(new BlockPos(offset, y, side)));
                    posts.add(context.getAbsolutePos(new BlockPos(-1, y, offset)));
                    posts.add(context.getAbsolutePos(new BlockPos(side, y, offset)));
                }
            }
            posts.forEach(post -> world.setBlockState(post, Blocks.BARRIER.getDefaultState()));
            onFinish(() -> posts.forEach(post -> world.setBlockState(post, Blocks.AIR.getDefaultState())));
        }

        CowEntity target(BlockPos relative) {
            // 不动、不出声的高血量牛，便于观察原版血量变化。
            CowEntity cow = context.spawnEntity(EntityType.COW, relative);
            cow.setAiDisabled(true /* aiDisabled */);
            cow.setSilent(true /* silent */);
            cow.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(TARGET_HEALTH);
            cow.setHealth((float) TARGET_HEALTH);
            spawned.add(cow);
            return cow;
        }

        void onFinish(Runnable cleanup) {
            // 结束或失败时额外要恢复的世界状态。
            cleanups.add(cleanup);
        }

        void later(int ticks, Runnable step) {
            // 固定延迟后执行；抛出时先清场。
            context.waitAndRun(ticks, () -> guarded(step));
        }

        void await(int ticks, BooleanSupplier condition, Supplier<String> failure, Runnable next) {
            // 条件在期限内满足就继续，到期仍不满足即失败。
            poll(ticks, condition, () -> {
                throw new GameTestException(failure.get());
            }, next);
        }

        void observe(int ticks, BooleanSupplier stop, Runnable next) {
            // 观察到期或提前停止都继续下一步。
            poll(ticks, stop, next, next);
        }

        private void poll(int ticks, BooleanSupplier condition, Runnable expired, Runnable next) {
            // 每 tick 检查一次；后续步骤或到期处理抛出时先清场。
            if (condition.getAsBoolean()) {
                guarded(next);
                return;
            }
            if (ticks == 0) {
                guarded(expired);
                return;
            }
            context.waitAndRun(1, () -> poll(ticks - 1, condition, expired, next));
        }

        private void guarded(Runnable step) {
            // 断言失败也不把带 AI 的实体留到后续批次。
            try {
                step.run();
            } catch (RuntimeException | Error thrown) {
                clean();
                throw thrown;
            }
        }

        void finish() {
            // 清场后报告通过。
            clean();
            context.complete();
        }

        private void clean() {
            // 移除生成的实体并恢复世界状态，只做一次。
            spawned.forEach(Entity::discard);
            spawned.clear();
            cleanups.forEach(Runnable::run);
            cleanups.clear();
        }
    }

    /** 逐 tick 记下一只实体出现过的式、决策动作、跃步、虚脱后的拉开与失败连击。 */
    private static final class Watch {
        private final MobEntity mob;
        private final double exhaustedRatio;
        private final Set<String> techniques = new LinkedHashSet<>();
        private final Set<Decision.Action> actions = EnumSet.noneOf(Decision.Action.class);
        private long lastDecisionTick = Long.MIN_VALUE;
        private long exhaustedTick = Long.MAX_VALUE;
        private boolean leaped;
        private boolean kitedAfterExhaustion;
        private int failureStreak;

        private Watch(MobEntity mob) {
            // 不关心虚脱的用例把门槛设为零。
            this(mob, 0);
        }

        private Watch(MobEntity mob, double exhaustedRatio) {
            // 较强一臂力量比低于门槛即记为虚脱。
            this.mob = mob;
            this.exhaustedRatio = exhaustedRatio;
        }

        MobEntity mob() {
            // 被观察的实体。
            return mob;
        }

        Watch observe() {
            // 记当前施放、跃步、失败连击与新增决策；返回自己便于在条件里链式读取。
            techniques.addAll(castTechniques(mob));
            long now = mob.getWorld().getTime();
            if (((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
                leaped = true;
            }
            failureStreak = Math.max(failureStreak, HumanoidBrain.failureStreak(mob.getUuid()));
            observeExhaustion(now);
            for (var logged : HumanoidBrain.decisions(mob.getUuid())) {
                if (logged.tick() <= lastDecisionTick) {
                    continue;
                }
                lastDecisionTick = logged.tick();
                Decision.Action action = logged.decision().action();
                actions.add(action);
                if (action == Decision.Action.KITE && logged.tick() >= exhaustedTick) {
                    kitedAfterExhaustion = true;
                }
            }
            return this;
        }

        private void observeExhaustion(long now) {
            // 第一次虚脱的世界 tick；身体快照不结清时间，按最近一次决策时的状态读。
            var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            if (body == null || exhaustedTick != Long.MAX_VALUE) {
                return;
            }
            double armRatio = HumanoidFighters.condition(BodyProtobuf.fromMessage(body.snapshot())).armStrengthRatio();
            if (armRatio < exhaustedRatio) {
                exhaustedTick = now;
            }
        }

        boolean cast(String technique) {
            // 是否施放过这一式。
            return techniques.contains(technique);
        }

        boolean castAny(String prefix) {
            // 是否施放过某个目录的式。
            return techniques.stream().anyMatch(technique -> technique.startsWith(prefix));
        }

        boolean castBefore(String technique, String laterPrefix) {
            // 这一式第一次出现早于该目录的第一式。
            for (String seen : techniques) {
                if (seen.equals(technique)) {
                    return true;
                }
                if (seen.startsWith(laterPrefix)) {
                    return false;
                }
            }
            return false;
        }

        boolean saw(Decision.Action action) {
            // 是否做过这一动作的决策。
            return actions.contains(action);
        }

        boolean sawAny(Set<Decision.Action> wanted) {
            // 是否做过其中任一动作的决策。
            return wanted.stream().anyMatch(actions::contains);
        }

        boolean leaped() {
            // 是否出现过跃步飞行。
            return leaped;
        }

        boolean kitedAfterExhaustion() {
            // 虚脱后是否做过背向跃步的决策。
            return kitedAfterExhaustion;
        }

        int failureStreak() {
            // 观察期内同一式连续失败的最多次数。
            return failureStreak;
        }

        @Override
        public String toString() {
            // 失败信息用的摘要。
            return mob.getUuidAsString() + " alive=" + mob.isAlive() + " hp=" + mob.getHealth()
                    + " techniques=" + techniques + " actions=" + actions + " leaped=" + leaped
                    + " exhaustedTick=" + exhaustedTick + " kited=" + kitedAfterExhaustion
                    + " failureStreak=" + failureStreak;
        }
    }
}
