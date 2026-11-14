package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Encounter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 类人大脑整体回归：撤退、对敌、准备、恢复、原版日常与两只真炁卫道士互打，均用正式目录和原版实体。 */
public final class HumanoidBrainGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_test");
    private static final String UNARMED_BATCH = "meridian_humanoid_test:brain_unarmed";
    private static final String ARMED_VILLAGER_BATCH = "meridian_humanoid_test:brain_armed_villager";
    private static final String LIGHT_STEP_BATCH = "meridian_humanoid_test:brain_light_step";
    private static final String BREATH_BATCH = "meridian_humanoid_test:brain_breath";
    private static final String CHARGE_BATCH = "meridian_humanoid_test:brain_charge";
    private static final String PILLAGER_BATCH = "meridian_humanoid_test:brain_pillager";
    private static final String SLEEP_BATCH = "meridian_humanoid_test:brain_sleep";
    private static final String CROWD_BATCH = "meridian_humanoid_test:brain_crowd";
    private static final String DUEL_BATCH = "meridian_humanoid_test:brain_duel";
    private static final String SUMMON_BATCH = "meridian_humanoid_test:brain_summon";
    private static final String STRAY_HIT_BATCH = "meridian_humanoid_test:brain_stray_hit";
    private static final String SCORE_DAMAGE_BATCH = "meridian_humanoid_test:brain_score_damage";
    private static final String SCORE_SWITCH_BATCH = "meridian_humanoid_test:brain_score_switch";
    private static final String LEFT_ALONE_BATCH = "meridian_humanoid_test:brain_left_alone";
    private static final String PRESSED_BATCH = "meridian_humanoid_test:brain_pressed";
    /** 10×10 的空结构，一百个村民一格一个都在结构以内。 */
    private static final String CROWD_STRUCTURE = "meridian_humanoid_test:crowd";
    private static final String RETREAT_STRUCTURE = "meridian_humanoid_test:retreat";
    private static final BlockPos RETREAT_SUBJECT = new BlockPos(20, 0, 20);
    private static final BlockPos RETREAT_NEAR = new BlockPos(20, 0, 22);
    private static final BlockPos RETREAT_MIDDLE = new BlockPos(20, 0, 25);
    private static final String EXHAUSTION_BATCH = "meridian_humanoid_test:brain_exhaustion";
    private static final String SPENT_LEGS_BATCH = "meridian_humanoid_test:brain_spent_legs";
    private static final String BASIC_PREFIX = "meridian_basic_techniques:";
    private static final String DOOR_BREAKER_PREFIX = "meridian_door_breaker:";
    private static final String KNOCK = DOOR_BREAKER_PREFIX + "knock";
    /** 破门中卷里要六块腿肌各出 100 W 的两式。 */
    private static final List<String> LEG_WORK = List.of(DOOR_BREAKER_PREFIX + "ram", DOOR_BREAKER_PREFIX + "close");
    private static final String CHARGE = BASIC_PREFIX + "charge";
    private static final String LIGHT_STEP = BASIC_PREFIX + "light_step";
    private static final String BREATH = BASIC_PREFIX + "breath";
    private static final BlockPos SUBJECT_POS = new BlockPos(2, 0, 2);
    private static final BlockPos NEAR_POS = new BlockPos(2, 0, 4);
    private static final BlockPos MIDDLE_POS = new BlockPos(2, 0, 7);
    private static final BlockPos BED_FOOT_POS = new BlockPos(4, 0, 2);
    /**
     * 远距两端，蓄力、掠夺者与对决共用，相距 6 格。26.2 的 GameTest 用屏障围住 8×8 结构（1.20.1 自动运行不围），
     * 原来 z=2 到 z=8 的远端正好落在墙里，所以两端整体挪进结构，间距不变。
     */
    private static final BlockPos FAR_SUBJECT_POS = new BlockPos(2, 0, 1);
    private static final BlockPos FAR_POS = new BlockPos(2, 0, 7);
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
    private static final int DUEL_TICKS = 3600;
    private static final int VANILLA_MELEE_GATE_TICKS = 20;
    private static final int DUEL_OPENING_TICKS = 25;
    private static final int SUMMON_TICKS = 300;
    /** 第一口咬到以后再看 3 秒（几次决策）。 */
    private static final int AFTER_BITE_TICKS = 60;
    private static final int VEX_COUNT = 2;
    /** 误伤以后再看 3 秒（几次决策）；原版受击记忆 100 tick 才过期，观察期内一直在。 */
    private static final int AFTER_STRAY_HIT_TICKS = 60;
    /** 同伴误伤的伤害：一下轻伤，不至于打倒或打残。 */
    private static final float STRAY_HIT = 1.0F;
    /** 远处尸壳先打的一下：0.6 × 8 / 20 = 0.24 分，盖过近 3 格的 0.09 分距离差。 */
    private static final float SCORE_HIT = 8.0F;
    /** 打分用例开场打的一下，两边一样重，只为让两只尸壳都留在受击记忆里。 */
    private static final float EVEN_HIT = 1.0F;
    private static final int SCORE_TICKS = 60;
    private static final int SWITCH_TICKS = 200;
    private static final int SWITCH_PERIOD = 20;
    private static final int SWITCH_LIMIT = 1;
    private static final BlockPos LEFT_POS = new BlockPos(1, 0, 5);
    private static final BlockPos RIGHT_POS = new BlockPos(6, 0, 5);
    private static final BlockPos MIDDLE_SUBJECT_POS = new BlockPos(3, 0, 1);
    private static final int DUEL_TIMEOUT = 4200;
    private static final int ROOM_EDGE = 9;
    private static final int ROOM_CEILING = 6;
    private static final BlockPos ROOM_SUBJECT = new BlockPos(2, 1, 2);
    private static final BlockPos ROOM_OPPONENT = new BlockPos(7, 1, 7);
    private static final long BRIEF_FATIGUE_MILLIS = 2_000;
    private static final int RETURN_TICKS = 200;
    private static final int PRESSED_TICKS = 240;
    private static final int HIT_INTERVAL_TICKS = 40;
    private static final float LIGHT_HIT = 1.0F;
    private static final double TICKS_PER_SECOND = 20;
    private static final int FAILURE_STREAK_LIMIT = 3;
    private static final double RETREAT_GAIN = 2;
    private static final double TARGET_HEALTH = 1000;
    private static final double FATIGUED_STRENGTH = 0.3;
    /** 腿肌里最强的臀大肌 650 W，乘 0.1 也只有 65 W，到不了撞门、掩门每块腿肌要的 100 W。 */
    private static final double SPENT_PEAK = 0.1;
    private static final long FATIGUE_MILLIS = 30_000;
    private static final long NIGHT_TIME = 13_000;
    private static final Set<String> ARM_MUSCLES = Set.of(HumanoidFighters.ARM_MUSCLE);
    private static final Set<String> LEG_MUSCLES = Set.of(HumanoidFighters.LEG_MUSCLE);
    private static final Set<String> LIMB_MUSCLES = Set.of(HumanoidFighters.ARM_MUSCLE, HumanoidFighters.LEG_MUSCLE);
    private static final Set<Decision.Action> RESPONSES = EnumSet.of(Decision.Action.INTERRUPT,
            Decision.Action.DODGE, Decision.Action.ABSORB);
    private static final Set<Decision.Action> RETREATS = EnumSet.of(Decision.Action.RETREAT, Decision.Action.KITE);

    @GameTest(maxTicks = LONG_TIMEOUT, environment = UNARMED_BATCH, structure = RETREAT_STRUCTURE)
    public void unarmedVillagerRetreatsFromVindicatorAndResumesRoutine(GameTestHelper context) {
        // 无功法的游炁村民被卫道士盯上时由大脑撤退而非原版恐慌；卫道士消失后交还原版日常。
        var arena = new Arena(context);
        Villager villager = arena.spawn(EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI, "unarmed", RETREAT_SUBJECT);
        Vindicator hunter = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.WANDERING_QI, "unarmed-hunter",
                RETREAT_NEAR);
        hunter.setNoAi(true /* aiDisabled */);
        hunter.setTarget(villager);
        double start = villager.distanceTo(hunter);
        BooleanSupplier retreated = () -> lastActionIs(villager, Decision.Action.RETREAT)
                && villager.distanceTo(hunter) > start + RETREAT_GAIN;
        arena.await(RETREAT_TICKS, retreated,
                () -> "村民没有撤退拉开：" + decisions(villager), () -> {
                    context.assertTrue(!villager.getBrain().isActive(Activity.PANIC), "村民进入了原版恐慌");
                    hunter.discard();
                    arena.await(RESUME_TICKS, () -> !HumanoidBrain.directing(villager) && routineRunning(villager),
                            () -> "威胁消失后村民没有回到原版日常：" + decisions(villager), arena::finish);
                });
    }

    @GameTest(maxTicks = LONG_TIMEOUT,
            environment = ARMED_VILLAGER_BATCH)
    public void trueQiVillagerWithUpperVolumeEngagesAndKnocks(GameTestHelper context) {
        // 学了破门上卷、持斧的真炁村民对游炁卫道士对敌，用上卷的式打伤对方。上卷叩门、撞门、劈门按权重抽，
        // 先抽到撞门再抽到劈门时，一记劈中头就能在叩门出现前打死卫道士，所以不限定叩门，打死也算打伤。
        var arena = new Arena(context);
        Villager villager = arena.spawn(EntityTypes.VILLAGER, HumanoidTier.TRUE_QI, "armed", SUBJECT_POS);
        villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        HumanoidTechniqueHost.prepare(villager);
        TechniqueFabric.service().learn(villager.getUUID(), DOOR_BREAKER_PREFIX + "upper");
        Vindicator hunter = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.WANDERING_QI, "armed-hunter",
                NEAR_POS);
        hunter.setNoAi(true /* aiDisabled */);
        hunter.setTarget(villager);
        double hp = partHpRatio(hunter);
        var watch = new Watch(villager);
        arena.await(ENGAGE_TICKS, () -> watch.observe().castAny(DOOR_BREAKER_PREFIX) && hurt(hunter, hp),
                () -> "村民没有用破门上卷伤到卫道士：" + watch + " 卫道士 " + condition(hunter) + " / "
                        + decisions(villager), () -> {
                    context.assertTrue(watch.saw(Decision.Action.STRIKE) || watch.saw(Decision.Action.INTERRUPT),
                            "村民没有对敌出手：" + watch);
                    arena.finish();
                });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = LIGHT_STEP_BATCH, structure = RETREAT_STRUCTURE)
    public void retreatingVillagerLightStepsAndLeapsAway(GameTestHelper context) {
        // 只会基础三卷的游炁村民撤退时先放轻步，再背向跃步拉开。
        var arena = new Arena(context);
        Villager villager = arena.spawn(EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI, "light-step",
                RETREAT_SUBJECT);
        Vindicator hunter = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.WANDERING_QI, "light-step-hunter",
                RETREAT_MIDDLE);
        hunter.setNoAi(true /* aiDisabled */);
        hunter.setTarget(villager);
        var watch = new Watch(villager);
        arena.await(LIGHT_STEP_TICKS,
                () -> watch.observe().cast(LIGHT_STEP) && watch.saw(Decision.Action.KITE) && watch.leaped(),
                () -> "撤退村民没有轻步后跃步：" + watch + " / " + decisions(villager), arena::finish);
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = EXHAUSTION_BATCH, structure = RETREAT_STRUCTURE)
    public void exhaustedVindicatorLeapsAwayInsteadOfTrading(GameTestHelper context) {
        // 双臂虚脱的持斧真炁卫道士被盯上时不原地对打，先轻步再背向跃步拉开。
        var arena = new Arena(context);
        Vindicator exhausted = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "exhausted",
                RETREAT_SUBJECT);
        exhausted.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        fatigue(exhausted, HumanoidSpecies.VINDICATOR, Fatigue.lasting(ARM_MUSCLES, FATIGUED_STRENGTH));
        Vindicator hunter = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.WANDERING_QI, "exhausted-hunter",
                RETREAT_NEAR);
        hunter.setNoAi(true /* aiDisabled */);
        hunter.setTarget(exhausted);
        double exhaustedRatio = HumanoidBrain.config().assessment().retreat().armStrengthRatio();
        var watch = new Watch(exhausted, exhaustedRatio);
        arena.await(EXHAUSTION_TICKS, () -> watch.observe().kitedAfterExhaustion() && watch.leaped(),
                () -> "虚脱卫道士没有背向跃步拉开：" + watch + " / " + decisions(exhausted), () -> {
                    context.assertTrue(watch.cast(LIGHT_STEP), "跃步前没有轻步：" + watch);
                    arena.finish();
                });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = SPENT_LEGS_BATCH)
    public void spentLegsKeepLegWorkOutOfCandidates(GameTestHelper context) {
        // 腿肌出不了功的持斧真炁卫道士：撞门、掩门照样绑得上、付得起真元，但做功预检不过，不进候选，
        // 免得照选照失败、攒够连续失败次数被暂停；只用双臂的叩门照旧在候选里。
        var arena = new Arena(context);
        Vindicator vindicator = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "spent-legs", SUBJECT_POS);
        vindicator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        HumanoidTechniqueHost.prepare(vindicator);
        List<String> rested = candidates(vindicator);
        context.assertTrue(rested.containsAll(LEG_WORK), "腿没累时撞门、掩门就不在候选里：" + rested);
        fatigue(vindicator, HumanoidSpecies.VINDICATOR, Fatigue.lasting(LEG_MUSCLES, SPENT_PEAK));
        var body = BodyProtobuf.fromMessage(((HumanoidEntityAccess) vindicator).meridian$humanoidBody().snapshot());
        var registry = TechniqueFabric.service().registry();
        for (String id : LEG_WORK) {
            TechniqueDefinition definition = registry.findLatest(id).orElseThrow();
            BindingOutcome outcome = TechniqueBinder.bind(body, definition);
            context.assertTrue(outcome.hasBinding(), id + " 腿累了就绑不上：" + bindingSummary(body, definition));
            var binding = outcome.getBinding();
            context.assertTrue(TechniqueAvailability.affordable(body, definition, binding), id + " 腿累了就付不起真元");
            context.assertTrue(!TechniqueAvailability.workable(body, definition, binding),
                    id + " 腿肌出不了 100 W 仍判做得了功");
        }
        List<String> spent = candidates(vindicator);
        context.assertTrue(spent.stream().noneMatch(LEG_WORK::contains), "腿累了撞门、掩门仍进候选：" + spent);
        context.assertTrue(spent.contains(KNOCK), "腿累了连只用双臂的叩门也不在候选里：" + spent);
        arena.finish();
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = BREATH_BATCH)
    public void idleFatiguedVillagerBreathes(GameTestHelper context) {
        // 无威胁时四肢疲劳达到调息门槛，村民自行调息。
        var arena = new Arena(context);
        Villager villager = arena.spawn(EntityTypes.VILLAGER, HumanoidTier.WANDERING_QI, "breath", SUBJECT_POS);
        fatigue(villager, HumanoidSpecies.VILLAGER, Fatigue.lasting(LIMB_MUSCLES, FATIGUED_STRENGTH));
        var watch = new Watch(villager);
        arena.await(BREATH_TICKS, () -> watch.observe().cast(BREATH),
                () -> "疲劳村民无威胁时没有调息：" + watch + " / " + decisions(villager) + " / "
                        + recoveryDiagnosis(villager), () -> {
                    context.assertTrue(watch.saw(Decision.Action.RECOVER), "调息不是恢复决策：" + watch);
                    arena.finish();
                });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = CHARGE_BATCH)
    public void axeVindicatorChargesBeforeStriking(GameTestHelper context) {
        // 持斧真炁卫道士在打击距离外先放蓄力，之后才出破门打击式。
        var arena = new Arena(context);
        Vindicator vindicator = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "charge",
                FAR_SUBJECT_POS);
        vindicator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        Cow target = arena.target(FAR_POS);
        vindicator.setTarget(target);
        var watch = new Watch(vindicator);
        arena.await(CHARGE_TICKS, () -> watch.observe().castAny(DOOR_BREAKER_PREFIX),
                () -> "卫道士没有出破门打击式：" + watch + " / " + decisions(vindicator), () -> {
                    context.assertTrue(watch.castBefore(CHARGE, DOOR_BREAKER_PREFIX), "打击前没有蓄力：" + watch);
                    arena.finish();
                });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = PILLAGER_BATCH)
    public void emptyTablePillagerStillShoots(GameTestHelper context) {
        // 空学卷表的掠夺者被接管后仍交还原版弩射击，不会呆住。
        var arena = new Arena(context);
        Pillager pillager = arena.spawn(EntityTypes.PILLAGER, HumanoidTier.WANDERING_QI, "pillager",
                FAR_SUBJECT_POS);
        pillager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
        Cow target = arena.target(FAR_POS);
        float health = target.getHealth();
        pillager.setTarget(target);
        arena.await(PILLAGER_TICKS, () -> target.getHealth() < health,
                () -> "空表掠夺者没有射中目标：" + decisions(pillager), arena::finish);
    }

    @GameTest(maxTicks = LONG_TIMEOUT, environment = SLEEP_BATCH)
    public void idleVillagerStillSleepsAtNight(GameTestHelper context) {
        // 夜里无威胁的村民照原版作息上床，大脑不记决策；26.2 的昼夜由维度默认时钟推进，改设该时钟。
        var arena = new Arena(context);
        var world = context.getLevel();
        var clock = world.dimensionType().defaultClock().orElseThrow();
        var clocks = world.getServer().clockManager();
        long previousTime = clocks.getTotalTicks(clock);
        arena.onFinish(() -> clocks.setTotalTicks(clock, previousTime));
        BlockPos head = bed(context);
        Villager villager = arena.spawnUnborn(EntityTypes.VILLAGER, "sleep", SUBJECT_POS);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(world.dimension(), head));
        clocks.setTotalTicks(clock, NIGHT_TIME);
        arena.await(SLEEP_TICKS, villager::isSleeping, () -> "无威胁村民夜里没有上床：" + decisions(villager), () -> {
            context.assertTrue(HumanoidBrain.decisions(villager.getUUID()).isEmpty(), "无威胁村民留下了大脑决策");
            arena.finish();
        });
    }

    @GameTest(maxTicks = CROWD_TIMEOUT, environment = CROWD_BATCH, structure = CROWD_STRUCTURE)
    public void hundredIdleVillagersPrepareNoBodiesForAMinute(GameTestHelper context) {
        // 一百个无威胁村民跑满一分钟；健康宿主照常惰性出生身体，大脑不准备身体会话，也不记决策。
        // GameTest 用屏障围住 10×10 结构，闲逛一分钟也走不出清场半径，撞不上前面批次留在原地的生物。
        var arena = new Arena(context);
        var villagers = new ArrayList<Villager>();
        for (int x = 0; x < CROWD_SIDE; x++) {
            for (int z = 0; z < CROWD_SIDE; z++) {
                String name = "crowd-" + x + "-" + z;
                villagers.add(arena.spawnUnborn(EntityTypes.VILLAGER, name, new BlockPos(x, 0, z)));
            }
        }
        arena.later(CROWD_TICKS, () -> {
            for (Villager villager : villagers) {
                String subject = villager.getStringUUID() + " 对手=" + HumanoidBrain.opponent(villager)
                        + " 决策=" + decisions(villager);
                context.assertTrue(!HumanoidTechniqueHost.hasSession(villager),
                        "无威胁村民准备了身体会话：" + subject);
                context.assertTrue(HumanoidBrain.decisions(villager.getUUID()).isEmpty(),
                        "无威胁村民留下了大脑决策：" + subject);
            }
            arena.finish();
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = SUMMON_BATCH)
    public void vexBitesKeepTheEvokerAsTheOpponent(GameTestHelper context) {
        // 召唤物仇恨：不动的唤魔者在 6 格外的平地上，它的两只恼鬼贴身咬真炁村民；
        // 村民的主对手一直是唤魔者，不换成贴身的恼鬼。唤魔者走得到，所以恼鬼贴身也不顺手打。
        var arena = new Arena(context);
        Villager villager = arena.spawn(EntityTypes.VILLAGER, HumanoidTier.TRUE_QI, "summon-villager",
                FAR_SUBJECT_POS);
        Evoker evoker = arena.spawnUnborn(EntityTypes.EVOKER, "summon-evoker", FAR_POS);
        evoker.setNoAi(true /* aiDisabled */);
        for (int index = 0; index < VEX_COUNT; index++) {
            Vex vex = arena.spawnUnborn(EntityTypes.VEX, "summon-vex-" + index, NEAR_POS);
            vex.setOwner(evoker);
            vex.setTarget(villager);
        }
        var opponents = new LinkedHashSet<String>();
        int[] tick = {0};
        int[] bitten = {-1};
        arena.await(SUMMON_TICKS, () -> {
            tick[0]++;
            if (bitten[0] < 0 && villager.getLastHurtByMob() instanceof Vex) {
                bitten[0] = tick[0];
            }
            HumanoidBrain.opponent(villager).ifPresent(opponent -> opponents.add(opponent.getType().toShortString()));
            return bitten[0] >= 0 && tick[0] >= bitten[0] + AFTER_BITE_TICKS;
        }, () -> "恼鬼一直没咬到村民：对手 " + opponents + " / " + decisions(villager), () -> {
            context.assertTrue(opponents.equals(Set.of(EntityTypes.EVOKER.toShortString())),
                    "村民的对手不只是唤魔者：" + opponents + " / " + decisions(villager));
            context.assertTrue(HumanoidBrain.opponent(villager).orElse(null) == evoker,
                    "恼鬼咬了 3 秒后村民的对手不是唤魔者：" + HumanoidBrain.opponent(villager));
            arena.finish();
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = STRAY_HIT_BATCH)
    public void allyStrayHitDoesNotStartAFight(GameTestHelper context) {
        // 同伴误伤：不动的掠夺者在 6 格外误伤真炁卫道士一下，原版受击记忆记着掠夺者；
        // 卫道士之后几次决策都不把同伴当对手，也不以它为目标。
        var arena = new Arena(context);
        Vindicator vindicator = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "stray-hit-vindicator",
                FAR_SUBJECT_POS);
        Pillager pillager = arena.spawnUnborn(EntityTypes.PILLAGER, "stray-hit-pillager", FAR_POS);
        pillager.setNoAi(true /* aiDisabled */);
        vindicator.hurt(vindicator.damageSources().mobAttack(pillager), STRAY_HIT);
        var remembered = vindicator.getLastHurtByMob();
        var opponents = new LinkedHashSet<String>();
        arena.observe(AFTER_STRAY_HIT_TICKS, () -> {
            HumanoidBrain.opponent(vindicator).ifPresent(opponent -> opponents.add(opponent.getType().toShortString()));
            return vindicator.getTarget() == pillager;
        }, () -> {
            context.assertTrue(remembered == pillager, "误伤没有留在原版受击记忆里：" + remembered);
            context.assertTrue(opponents.isEmpty(), "误伤后卫道士把同伴当了对手：" + opponents + " / "
                    + decisions(vindicator));
            context.assertTrue(vindicator.getTarget() != pillager, "误伤后卫道士以掠夺者为目标");
            arena.finish();
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = SCORE_DAMAGE_BATCH)
    public void scoredThreatPrefersTheFartherOneWhoHurtMe(GameTestHelper context) {
        // 选敌打分：两只不动的尸壳都盯着真炁卫道士，近的 3 格、远的 6 格；远的先打了它一下。
        // 只按远近会选近的；打分里「近期对我造成的伤害」盖过距离差，第一次决策就选远的，之后也不换。
        var arena = new Arena(context);
        Vindicator vindicator = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "score-vindicator",
                FAR_SUBJECT_POS);
        Husk near = arena.dummy("score-near", NEAR_POS, vindicator);
        Husk far = arena.dummy("score-far", FAR_POS, vindicator);
        vindicator.hurt(vindicator.damageSources().mobAttack(far), SCORE_HIT);
        Map<LivingEntity, String> labels = Map.of(near, "近", far, "远");
        var opponents = new LinkedHashSet<String>();
        int[] engaged = {-1};
        int[] tick = {0};
        arena.await(SCORE_TICKS * 2, () -> {
            tick[0]++;
            HumanoidBrain.opponent(vindicator).ifPresent(opponent -> opponents.add(label(opponent, labels)));
            if (engaged[0] < 0 && !opponents.isEmpty()) {
                engaged[0] = tick[0];
            }
            return engaged[0] >= 0 && tick[0] >= engaged[0] + SCORE_TICKS;
        }, () -> "卫道士一直没有对手：" + decisions(vindicator), () -> {
            context.assertTrue(opponents.equals(Set.of("远")), "卫道士没有只盯打过它的远处尸壳：" + opponents
                    + " 记着的伤害 " + HumanoidThreats.recentDamage(vindicator, far, HumanoidBrain.config().threat())
                    + " / " + decisions(vindicator));
            arena.finish();
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = SCORE_SWITCH_BATCH)
    public void scoredThreatDoesNotFlipBetweenEvenThreats(GameTestHelper context) {
        // 换目标惯性：两只不动的尸壳各打过真炁卫道士一下，之后每秒轮流盯它、轮流不盯。
        // 不带惯性时每秒都会换去正盯着它的那只；带惯性与换目标门槛，10 秒内最多换一次。
        var arena = new Arena(context);
        Vindicator vindicator = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "switch-vindicator",
                MIDDLE_SUBJECT_POS);
        Husk left = arena.dummy("switch-left", LEFT_POS, vindicator);
        Husk right = arena.dummy("switch-right", RIGHT_POS, vindicator);
        vindicator.hurt(vindicator.damageSources().mobAttack(left), EVEN_HIT);
        vindicator.hurt(vindicator.damageSources().mobAttack(right), EVEN_HIT);
        right.setTarget(null);
        Map<LivingEntity, String> labels = Map.of(left, "左", right, "右");
        var history = new ArrayList<String>();
        int[] tick = {0};
        arena.observe(SWITCH_TICKS, () -> {
            tick[0]++;
            if (tick[0] % SWITCH_PERIOD == 0) {
                boolean leftTurn = tick[0] / SWITCH_PERIOD % 2 == 0;
                left.setTarget(leftTurn ? vindicator : null);
                right.setTarget(leftTurn ? null : vindicator);
            }
            HumanoidBrain.opponent(vindicator).map(opponent -> label(opponent, labels))
                    .filter(current -> history.isEmpty() || !history.getLast().equals(current))
                    .ifPresent(history::add);
            return false;
        }, () -> {
            context.assertFalse(history.isEmpty(), "卫道士一直没有对手：" + decisions(vindicator));
            context.assertTrue(history.size() - 1 <= SWITCH_LIMIT, "10 秒内换对手超过一次：" + history
                    + " / " + decisions(vindicator));
            arena.finish();
        });
    }

    private static String label(LivingEntity opponent, Map<LivingEntity, String> labels) {
        // 失败信息里用一个字区分两只尸壳，别的对手写实体类型。
        return labels.getOrDefault(opponent, opponent.getType().toShortString());
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = LEFT_ALONE_BATCH, structure = CROWD_STRUCTURE)
    public void recoveredVindicatorReturnsToFightWhenLeftAloneIndoors(GameTestHelper context) {
        // 8×8 封闭石室退不到安全距离。持斧真炁卫道士双臂虚脱 2 秒、转入撤退；对手站着不动、也没打它，
        // 臂力回来以后不必退到安全距离，连着没被逼够配置的秒数就回到对敌。
        var arena = new Arena(context);
        enclose(context);
        Vindicator vindicator = retreater(arena, "left-alone-vindicator");
        Cow bystander = arena.target(ROOM_OPPONENT);
        vindicator.setTarget(bystander);
        var watch = new StanceWatch(vindicator, bystander);
        arena.await(RETURN_TICKS, () -> watch.observe().returned(),
                () -> "没被逼的卫道士没有回到对敌：" + watch + " / " + decisions(vindicator), () -> {
                    assertIndoors(context, watch);
                    var rules = HumanoidBrain.config();
                    double waited = rules.assessment().recovery().unpressedSeconds() * TICKS_PER_SECOND
                            - rules.schedule().decisionIntervalTicks();
                    context.assertTrue(watch.retreatTicks() >= waited, "没等够没被逼的秒数就回头了：" + watch);
                    arena.finish();
                });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, environment = PRESSED_BATCH, structure = CROWD_STRUCTURE)
    public void recoveredVindicatorKeepsAwayWhileStillBeingHit(GameTestHelper context) {
        // 同一间石室、同样虚脱 2 秒；这回对手每 2 秒打到它一下。完好率和臂力都过了回归门槛，
        // 但一直被逼着，12 秒里姿态始终是撤退。
        var arena = new Arena(context);
        enclose(context);
        Vindicator vindicator = retreater(arena, "pressed-vindicator");
        Cow striker = arena.target(ROOM_OPPONENT);
        vindicator.setTarget(striker);
        var watch = new StanceWatch(vindicator, striker);
        var hits = new int[1];
        arena.observe(PRESSED_TICKS, () -> {
            if (hits[0]++ % HIT_INTERVAL_TICKS == 0) {
                vindicator.hurt(vindicator.damageSources().mobAttack(striker), LIGHT_HIT);
            }
            return watch.observe().returned();
        }, () -> {
            context.assertTrue(watch.retreated() && !watch.returned(),
                    "一直挨打的卫道士回到了对敌：" + watch + " / " + decisions(vindicator));
            assertIndoors(context, watch);
            var body = BodyProtobuf.fromMessage(((HumanoidEntityAccess) vindicator).meridian$humanoidBody().snapshot());
            var condition = HumanoidFighters.condition(body);
            var recovery = HumanoidBrain.config().assessment().recovery();
            context.assertTrue(condition.hpRatio() >= recovery.hpRatio()
                            && condition.armStrengthRatio() >= recovery.armStrengthRatio(),
                    "夹具应当只剩「被逼着」这一条拦着：" + condition);
            arena.finish();
        });
    }

    private static Vindicator retreater(Arena arena, String name) {
        // 持斧真炁卫道士，双臂短暂虚脱：先因臂力转入撤退，2 秒后臂力回来，剩下只看有没有拉开。
        Vindicator vindicator = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, name, ROOM_SUBJECT);
        vindicator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        fatigue(vindicator, HumanoidSpecies.VINDICATOR, new Fatigue(ARM_MUSCLES, FATIGUED_STRENGTH,
                BRIEF_FATIGUE_MILLIS));
        return vindicator;
    }

    private static void assertIndoors(GameTestHelper context, StanceWatch watch) {
        // 石室里始终没到安全距离，姿态变化不是靠拉开距离来的。
        double safeDistance = HumanoidBrain.config().response().tactics().safeDistance();
        context.assertTrue(watch.farthest() < safeDistance, "石室里不该退得到安全距离：" + watch);
    }

    private static void enclose(GameTestHelper context) {
        // 8×8 内室有完整地面、四壁与顶棚，走不出去。
        for (int x = 0; x <= ROOM_EDGE; x++) {
            for (int y = 0; y <= ROOM_CEILING; y++) {
                for (int z = 0; z <= ROOM_EDGE; z++) {
                    if (x == 0 || x == ROOM_EDGE || y == 0 || y == ROOM_CEILING || z == 0 || z == ROOM_EDGE) {
                        context.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
    }

    @GameTest(maxTicks = DUEL_TIMEOUT, environment = DUEL_BATCH)
    public void trueQiVindicatorsDuelForThreeMinutes(GameTestHelper context) {
        // 两只持斧真炁卫道士互设目标打三分钟或打到一方倒下，检查应对、准备与失败上限；虚脱后拉开另有确定性用例。
        // 原版近战每 20 tick 才查一次能否开打，刚出生的生物头一次查不受限，互设目标的同一刻就能抢在大脑前开砍。
        // 所以世界时刻不到 20 tick 时先等到 20 tick 再出生，出生后不设目标空等，原版查过两次以后再互设目标，
        // 让单跑和整体回归都由大脑先接手开局。
        var arena = new Arena(context);
        long warmup = Math.max(0, VANILLA_MELEE_GATE_TICKS - context.getLevel().getGameTime());
        arena.later((int) warmup, () -> {
            Vindicator first = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "duel-first",
                    FAR_SUBJECT_POS);
            Vindicator second = arena.spawn(EntityTypes.VINDICATOR, HumanoidTier.TRUE_QI, "duel-second", FAR_POS);
            for (Vindicator fighter : List.of(first, second)) {
                fighter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            }
            arena.later(DUEL_OPENING_TICKS, () -> duel(context, arena, first, second));
        });
    }

    private static void duel(GameTestHelper context, Arena arena, Vindicator first, Vindicator second) {
        // 互设目标后开打，到时或一方倒下时写日志并断言。
        first.setTarget(second);
        second.setTarget(first);
        var watches = List.of(new Watch(first), new Watch(second));
        long start = context.getLevel().getGameTime();
        arena.observe(DUEL_TICKS, () -> duelOver(watches), () -> {
            logDuel(watches, context.getLevel().getGameTime() - start);
            assertDuel(context, watches);
            arena.finish();
        });
    }

    private static boolean duelOver(List<Watch> watches) {
        // 每 tick 记录双方，并记下各自有没有被对方来袭罩住；任一方倒下即结束。
        for (Watch watch : watches) {
            watch.observe();
            watches.stream().filter(other -> other != watch).forEach(other -> watch.observeCover(other.mob()));
        }
        return watches.stream().anyMatch(watch -> !watch.mob().isAlive());
    }

    private static void logDuel(List<Watch> watches, long ticks) {
        // 每局把走的分支写进 GameTest 日志，便于跨局统计抖动；只记不断言，先于断言写，失败的局也有记录。
        // 按做过撤退或拉开决策的一方数量分：没有为交手，一方为一方撤退，双方为双方撤退。
        long retreated = watches.stream().filter(watch -> watch.sawAny(RETREATS)).count();
        String branch = "交手";
        if (retreated == watches.size()) {
            branch = "双方撤退";
        } else if (retreated > 0) {
            branch = "一方撤退";
        }
        long struck = watches.stream().filter(Watch::struckByTechnique).count();
        long fallen = watches.stream().filter(watch -> !watch.mob().isAlive()).count();
        LOGGER.info("卫道士对决分支：{}（撤退 {}，被打中 {}，倒下 {}，共 {} 方，{} tick）",
                branch, retreated, struck, fallen, watches.size(), ticks);
    }

    private static void assertDuel(GameTestHelper context, List<Watch> watches) {
        // 至少一方被破门打击式打中过；真被来袭罩住过的一方应对过；双方都蓄过力，同一式连续失败不到三次。
        // 速胜局里胜方可能还没被罩到就打倒了对手，撤退方一直拉开也罩不到，这两种都不要求应对；
        // 双方都撤时可以谁也不起手、谁也没打中。
        // 两只卫道士是原版同伴，互为目标才打得到，命中断言守住「同伴但正是我的敌人」交给宿主判定。
        boolean struck = watches.stream().anyMatch(watch -> watch.castAny(DOOR_BREAKER_PREFIX));
        boolean hit = watches.stream().anyMatch(Watch::struckByTechnique);
        boolean bothRetreated = watches.stream().allMatch(watch -> watch.sawAny(RETREATS));
        context.assertTrue(struck || bothRetreated, "双方都没有起手打击式，也不是都在撤退：" + watches);
        context.assertTrue(hit || bothRetreated, "双方都没有被打击式打中过，也不是都在撤退：" + watches);
        for (Watch watch : watches) {
            if (watch.covered()) {
                String duel = watches + " / " + decisions(watch.mob());
                context.assertTrue(watch.sawAny(RESPONSES), "这一方被来袭罩住过，却没有打断、躲或硬抗：" + duel);
            }
            context.assertTrue(watch.cast(CHARGE), "一方开打前没有蓄力：" + watch);
            context.assertTrue(watch.failureStreak() < FAILURE_STREAK_LIMIT, "同一式连续失败三次以上：" + watch);
        }
    }

    private static boolean lastActionIs(Mob mob, Decision.Action action) {
        // 最近一条决策是否为该动作；没有决策时为否。
        var log = HumanoidBrain.decisions(mob.getUUID());
        return !log.isEmpty() && log.get(log.size() - 1).decision().action() == action;
    }

    private static String decisions(Mob mob) {
        // 失败信息里列出最近的决策与理由。
        return HumanoidBrain.decisions(mob.getUUID()).stream()
                .map(logged -> logged.tick() + ":" + logged.decision().action()
                        + "(" + logged.decision().reason() + ")")
                .toList()
                .toString();
    }

    private static boolean routineRunning(Villager villager) {
        // 原版大脑重新跑任务，或已有日常走路目标。
        var brain = villager.getBrain();
        return !brain.getRunningBehaviors().isEmpty() || brain.hasMemoryValue(MemoryModuleType.WALK_TARGET);
    }

    private static boolean hurt(Mob mob, double initialHpRatio) {
        // 倒下或权威身体完好率比开始时低；倒下后不再读身体。
        return !mob.isAlive() || partHpRatio(mob) < initialHpRatio;
    }

    private static String condition(Mob mob) {
        // 失败信息里列出存活与完好率，区分没打中和打完了。
        if (!mob.isAlive()) {
            return "已倒下";
        }
        return String.format(Locale.ROOT, "存活，完好率 %.3f", partHpRatio(mob));
    }

    private static double partHpRatio(Mob mob) {
        // 权威身体的完好率，不结清时间。
        var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
        return BodyProtobuf.fromMessage(body.snapshot()).partHpRatio();
    }

    private static List<String> castTechniques(Mob mob) {
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

    private static List<String> candidates(Mob mob) {
        // 读候选时绑得上、付得起、做得了功也持物合规的式；不看对手、冷却与选式权重。
        var body = BodyProtobuf.fromMessage(((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot());
        var readout = HumanoidRepertoires.read(mob, body, Optional.empty(), technique -> false);
        return readout.context().availability().bindings().keySet().stream()
                .map(reference -> reference.getId())
                .toList();
    }

    private static BlockPos bed(GameTestHelper context) {
        // 在村民旁放一张朝东的床，返回床头的世界坐标。
        var state = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        BlockPos headPos = BED_FOOT_POS.east();
        context.setBlock(BED_FOOT_POS, state.setValue(BedBlock.PART, BedPart.FOOT));
        context.setBlock(headPos, state.setValue(BedBlock.PART, BedPart.HEAD));
        return context.absolutePos(headPos);
    }

    /** 一段疲劳夹具：哪些标签的肌肉、力量与功率降到几成、持续多少毫秒。 */
    private record Fatigue(Set<String> muscles, double peak, long millis) {
        static Fatigue lasting(Set<String> muscles, double peak) {
            // 用例全程都在疲劳里。
            return new Fatigue(muscles, peak, FATIGUE_MILLIS);
        }
    }

    private static void fatigue(Mob mob, HumanoidSpecies species, Fatigue fatigue) {
        // 先准备会话学卷，再把带疲劳的快照恢复成唯一 Store 并重新准备会话。
        HumanoidTechniqueHost.prepare(mob);
        var fatigued = fatigued(((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot(), fatigue);
        HumanoidTechniqueHost.removed(mob);
        bind(mob, species, fatigued);
        HumanoidTechniqueHost.prepare(mob);
    }

    private static String recoveryDiagnosis(Mob mob) {
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

    private static BodyMessages.BodySnapshot fatigued(BodyMessages.BodySnapshot body, Fatigue fatigue) {
        // 指定标签的肌肉施加一段恒定的力量与功率下降；取 FATIGUED_STRENGTH 时疲劳比高于调息门槛，
        // 较强一臂也低于虚脱门槛。时长一过曲线失效，力量回到原样。
        Set<String> muscles = fatigue.muscles();
        double peak = fatigue.peak();
        var result = body.toBuilder();
        var start = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(0)
                .setValue(1)
                .build();
        var end = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(fatigue.millis())
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
                    .setStrengthPeak(peak)
                    .setBurstPeak(peak)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .addCurve(start)
                    .addCurve(end)
                    .build();
            result.addModifiers(modifier);
        }
        return result.build();
    }

    private static void bind(Mob mob, HumanoidSpecies species, BodyMessages.BodySnapshot snapshot) {
        // 从合法快照恢复唯一 Store，测试不直接改运行中的权威状态。
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        var body = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(), species);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(body);
    }

    /** 一个用例的场地：记下生成的实体，结束或失败时一并移除，免得带 AI 的实体留到后续批次。 */
    private static final class Arena {
        private final GameTestHelper context;
        private final List<Entity> spawned = new ArrayList<>();
        private final List<Runnable> cleanups = new ArrayList<>();

        private Arena(GameTestHelper context) {
            // 场地只属于一个用例；每个用例独占一个批次，开场先清掉半径内前面批次留下的生物。
            this.context = context;
            clearMobs(HumanoidBrain.config().threat().forgetDistance() + CROWD_SIDE);
        }

        <T extends Mob> T spawn(EntityType<T> type, HumanoidTier tier, String name, BlockPos relative) {
            // 按指定档位出生身体后再加入世界。
            T mob = placed(type, name, relative);
            HumanoidSpeciesBirths.birth(mob, tier);
            return joined(mob);
        }

        <T extends Mob> T spawnUnborn(EntityType<T> type, String name, BlockPos relative) {
            // 不出生身体，留给宿主惰性出生。
            return joined(placed(type, name, relative));
        }

        private <T extends Mob> T placed(EntityType<T> type, String name, BlockPos relative) {
            // 固定 UUID 让选式种子可重复；整体回归每局新建世界，只有在保留下来的世界里重跑时才会碰上同 UUID 的实体，
            // 先移除，否则加入世界会失败。
            UUID id = UUID.nameUUIDFromBytes(("humanoid-brain/" + name).getBytes(StandardCharsets.UTF_8));
            Entity stale = context.getLevel().getEntity(id);
            if (stale != null) {
                stale.discard();
            }
            T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
            Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
            mob.setUUID(id);
            mob.snapTo(position.x, position.y, position.z, 0, 0);
            mob.setPersistenceRequired();
            return mob;
        }

        private <T extends Mob> T joined(T mob) {
            // 加入世界并登记清场。
            context.assertTrue(context.getLevel().addFreshEntity(mob), "实体未加入世界：" + mob.getStringUUID());
            spawned.add(mob);
            return mob;
        }

        private void clearMobs(double radius) {
            // 留在场地外的生物会被原版敌对感知读到而成为敌意来源；半径覆盖放下距离与一百村民的方阵。
            AABB area = new AABB(context.absolutePos(BlockPos.ZERO)).inflate(radius);
            context.getLevel().getEntitiesOfClass(Mob.class, area, mob -> true).forEach(Entity::discard);
        }

        Husk dummy(String name, BlockPos relative, LivingEntity target) {
            // 不动、不出声、晒不着火的高血量尸壳，开场就以 target 为目标；只当敌意来源，不还手。
            Husk husk = spawnUnborn(EntityTypes.HUSK, name, relative);
            husk.setNoAi(true /* aiDisabled */);
            husk.setSilent(true /* silent */);
            husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
            husk.setHealth((float) TARGET_HEALTH);
            husk.setTarget(target);
            return husk;
        }

        Cow target(BlockPos relative) {
            // 不动、不出声的高血量牛，便于观察原版血量变化。
            Cow cow = context.spawn(EntityTypes.COW, relative);
            cow.setNoAi(true /* aiDisabled */);
            cow.setSilent(true /* silent */);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
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
            context.runAfterDelay(ticks, () -> guarded(step));
        }

        void await(int ticks, BooleanSupplier condition, Supplier<String> failure, Runnable next) {
            // 条件在期限内满足就继续，到期仍不满足即失败。
            poll(ticks, condition, () -> {
                throw context.assertionException(Component.literal(failure.get()));
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
            context.runAfterDelay(1, () -> poll(ticks - 1, condition, expired, next));
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
            context.succeed();
        }

        private void clean() {
            // 移除生成的实体并恢复世界状态，只做一次。
            spawned.forEach(Entity::discard);
            spawned.clear();
            cleanups.forEach(Runnable::run);
            cleanups.clear();
        }
    }

    /** 逐 tick 记下一只实体的姿态：第几 tick 转入撤退、第几 tick 回到对敌，以及离对手最远到过多少格。 */
    private static final class StanceWatch {
        private static final int UNSEEN = -1;
        private final Mob mob;
        private final Entity opponent;
        private int ticks;
        private int retreatedAt = UNSEEN;
        private int returnedAt = UNSEEN;
        private double farthest;

        private StanceWatch(Mob mob, Entity opponent) {
            this.mob = mob;
            this.opponent = opponent;
        }

        StanceWatch observe() {
            // 先见到撤退，之后第一次见到对敌才算回头。
            ticks++;
            farthest = Math.max(farthest, mob.distanceTo(opponent));
            var stance = HumanoidBrain.stance(mob.getUUID());
            if (retreatedAt == UNSEEN && stance.filter(HumanoidAssessment.Stance.RETREAT::equals).isPresent()) {
                retreatedAt = ticks;
            }
            if (retreated() && !returned() && stance.filter(HumanoidAssessment.Stance.ENGAGE::equals).isPresent()) {
                returnedAt = ticks;
            }
            return this;
        }

        boolean retreated() {
            return retreatedAt != UNSEEN;
        }

        boolean returned() {
            return returnedAt != UNSEEN;
        }

        int retreatTicks() {
            // 从转入撤退到回到对敌隔了多少 tick。
            return returnedAt - retreatedAt;
        }

        double farthest() {
            return farthest;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "第 %d tick 撤退，第 %d tick 回头，最远 %.1f 格，共看了 %d tick",
                    retreatedAt, returnedAt, farthest, ticks);
        }
    }

    /** 逐 tick 记下一只实体出现过的式、决策动作、跃步、被打击式打中、被来袭罩住、虚脱后的拉开与失败连击。 */
    private static final class Watch {
        private final Mob mob;
        private final double exhaustedRatio;
        private final Set<String> techniques = new LinkedHashSet<>();
        private final Set<Decision.Action> actions = EnumSet.noneOf(Decision.Action.class);
        private long lastDecisionTick = Long.MIN_VALUE;
        private long exhaustedTick = Long.MAX_VALUE;
        private boolean leaped;
        private boolean struckByTechnique;
        private boolean kitedAfterExhaustion;
        private int failureStreak;
        private int coveredRun;
        private boolean covered;

        private Watch(Mob mob) {
            // 不关心虚脱的用例把门槛设为零。
            this(mob, 0);
        }

        private Watch(Mob mob, double exhaustedRatio) {
            // 较强一臂力量比低于门槛即记为虚脱。
            this.mob = mob;
            this.exhaustedRatio = exhaustedRatio;
        }

        Mob mob() {
            // 被观察的实体。
            return mob;
        }

        Watch observe() {
            // 记当前施放、跃步、受击、失败连击与新增决策；返回自己便于在条件里链式读取。
            // 原版只在受伤成功时记来源并保留 40 tick，逐 tick 读不会漏；伤害类型都已注册，键必然存在。
            techniques.addAll(castTechniques(mob));
            long now = mob.level().getGameTime();
            if (((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
                leaped = true;
            }
            var damage = mob.getLastDamageSource();
            if (damage != null && damage.typeHolder().unwrapKey().orElseThrow().identifier().toString()
                    .startsWith(DOOR_BREAKER_PREFIX)) {
                struckByTechnique = true;
            }
            failureStreak = Math.max(failureStreak, HumanoidBrain.failureStreak(mob.getUUID()));
            observeExhaustion(now);
            for (var logged : HumanoidBrain.decisions(mob.getUUID())) {
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

        void observeCover(Mob opponent) {
            // 对手下一击在反应窗口内罩住我，且连续超过一个决策间隔，大脑必有一次决策看到它；估计和大脑应对用的是同一份。
            // 跃步飞行中大脑不决策，这段不计。
            var body = ((HumanoidEntityAccess) mob).meridian$humanoidBody();
            if (body == null || !mob.isAlive() || ((TechniqueLeapAccess) mob).meridian$getLeap() != null) {
                coveredRun = 0;
                return;
            }
            var observed = new HumanoidOpponent(opponent, TechniqueFabric.service().observe(opponent.getUUID()));
            long window = HumanoidBrain.config().response().tactics().reaction().windowMillis();
            boolean coveredNow = HumanoidStrikeEstimates.incoming(mob, BodyProtobuf.fromMessage(body.snapshot()),
                            observed)
                    .filter(incoming -> incoming.has(Encounter.Incoming.Trait.COVERS)
                            && incoming.millisUntilHit() <= window)
                    .isPresent();
            if (!coveredNow) {
                coveredRun = 0;
                return;
            }
            coveredRun++;
            if (coveredRun > HumanoidBrain.config().schedule().decisionIntervalTicks()) {
                covered = true;
            }
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

        boolean struckByTechnique() {
            // 是否受过破门目录打击式的伤害。
            return struckByTechnique;
        }

        boolean kitedAfterExhaustion() {
            // 虚脱后是否做过背向跃步的决策。
            return kitedAfterExhaustion;
        }

        int failureStreak() {
            // 观察期内同一式连续失败的最多次数。
            return failureStreak;
        }

        boolean covered() {
            // 是否被对方来袭罩住过一个决策间隔以上。
            return covered;
        }

        @Override
        public String toString() {
            // 失败信息用的摘要。
            return mob.getStringUUID() + " alive=" + mob.isAlive() + " hp=" + mob.getHealth()
                    + " techniques=" + techniques + " actions=" + actions + " leaped=" + leaped
                    + " struck=" + struckByTechnique + " covered=" + covered
                    + " exhaustedTick=" + exhaustedTick + " kited=" + kitedAfterExhaustion
                    + " failureStreak=" + failureStreak;
        }
    }
}
