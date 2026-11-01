package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 正式功法目录、身体宿主和类人大脑的完整装配回归；破门用例的批次只发破门卷。 */
public final class HumanoidTechniqueGameTest {
    private static final String TECHNIQUE_BATCH = "meridian_humanoid_test:technique";
    private static final String DOOR_ALLOW_BATCH = "meridian_humanoid_test:door_allow";
    private static final String DOOR_DENY_BATCH = "meridian_humanoid_test:door_deny";
    private static final int ORIGIN = 2;
    private static final int TARGET_Z = 4;
    private static final int FLOOR_EDGE = 6;
    private static final int DEFAULT_DECISION_INTERVAL_TICKS = 10;
    private static final int DECISION_TICKS = DEFAULT_DECISION_INTERVAL_TICKS + 2;
    private static final int DECISION_CYCLES = 3;
    private static final int VOLUME_SAMPLE_LIMIT = 1000;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int KNOCK_HIT_TICKS = 34;
    private static final int CLEAVE_HIT_TICKS = 64;
    private static final int FOLLOW_UP_TICKS = 96;
    private static final int RECOVERY_TICKS = 125;
    private static final int TEST_TIMEOUT = 220;
    private static final int UPPER_COUNT = 3;
    private static final int MIDDLE_COUNT = 6;
    private static final long STAGGER_MILLIS = 200;
    private static final double STAGGER_MOVEMENT = 0.5;
    private static final float LIGHT_HIT = 0.2F;
    private static final float HEAVY_HIT = 4;
    private static final int ALL_COUNT = 8;
    private static final double TARGET_HEALTH = 1000;
    private static final double FAR_DISTANCE = 2.9;
    private static final double EXHAUSTED_RATIO = 0.6;
    private static final long EXHAUSTION_MILLIS = 1000;
    private static final double IMPULSE_SPEED = 4;
    private static final double IMPULSE_PER_TICK = IMPULSE_SPEED / 20;
    private static final double HIGH_TARGET_OFFSET = 4;
    private static final double TARGET_HORIZONTAL_OFFSET = 3;
    private static final double VERTICAL_VELOCITY = 0.4;
    private static final double EPSILON = 0.000001;
    private static final double MOVEMENT_MULTIPLIER = 0.4;
    private static final long MILLIS_PER_TICK = 50;
    private static final int FINISH_MARGIN_TICKS = 2;
    private static final String PREFIX = "meridian_door_breaker:";

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void brainThrottlesDecisionsAndSkipsMissingThreatAndActiveCast(GameTestHelper context) {
        // 手动推进原版实体年龄并直接调用大脑入口，数决策记录判断何时读身体决策；26.2 的目标选择器不再带性能分析器。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        mob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        mob.setNoAi(false /* aiDisabled */);
        var goals = new GoalSelector();
        var targets = new GoalSelector();
        runDecisions(mob, goals, targets);
        context.assertTrue(HumanoidBrain.decisions(mob.getUUID()).isEmpty(), "无威胁时仍进入决策");
        mob.setTarget(target);
        runDecisions(mob, goals, targets);
        int decided = HumanoidBrain.decisions(mob.getUUID()).size();
        context.assertTrue(decided == DECISION_CYCLES, "决策未按配置周期限流：" + decided);
        context.assertTrue(!progress(mob).hasActiveCast(), "无工具时意外施放");
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        start(context, mob, "knock", target);
        context.assertTrue(HumanoidTechniqueHost.isCasting(mob), "施放未锁定");
        String castId = progress(mob).getActiveCast().getRequest().getCastId();
        var beforeLockedBody = snapshot(mob);
        runDecisions(mob, goals, targets);
        context.assertTrue(HumanoidBrain.decisions(mob.getUUID()).size() == decided, "施放中重复进入决策");
        context.assertTrue(snapshot(mob).equals(beforeLockedBody), "施放中重复准备或改变身体");
        context.assertTrue(progress(mob).getActiveCast().getRequest().getCastId().equals(castId), "施放被重复启动");
        mob.setNoAi(true /* aiDisabled */);
        HumanoidTechniqueHost.removed(mob);
        context.succeed();
    }

    private static void runDecisions(Vindicator mob, GoalSelector goals, GoalSelector targets) {
        // 连续整周期覆盖任意实体相位，同步执行不混入世界里的原版 Goal。
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        for (int tick = 0; tick < DECISION_CYCLES * interval; tick++) {
            mob.tickCount++;
            HumanoidBrain.tick(mob, goals, targets);
        }
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void realAiCastsDamagesAndKeepsAxeDurability(GameTestHelper context) {
        // 只提供原版目标，让 serverAiStep 的真实接线自行选择和施放。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        float health = target.getHealth();
        ItemStack axe = mob.getMainHandItem();
        mob.setTarget(target);
        mob.setNoAi(false /* aiDisabled */);
        context.runAfterDelay(DECISION_TICKS, () -> {
            context.assertTrue(progress(mob).hasActiveCast(), "原版 AI 循环未启动功法");
            context.assertTrue(HumanoidTechniqueHost.isCasting(mob), "AI 起手没有记录施放");
            context.assertTrue(mob.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0,
                    "AI 施放期间无法移动");
            assertPaRequest(context, mob);
            mob.setNoAi(true /* aiDisabled */);
            long elapsedMillis = HumanoidFabric.now().getElapsedMillis();
            long remainingMillis = progress(mob).getActiveCast().getRecoveryEndMillis() - elapsedMillis;
            long remainingTicks = remainingMillis / MILLIS_PER_TICK + FINISH_MARGIN_TICKS;
            context.runAfterDelay(remainingTicks, () -> {
                context.assertTrue(target.getHealth() < health, "真炁卫道士施放未造成真实伤害");
                context.assertTrue(axe.getDamageValue() == 0 && axe.getCount() == 1, "生物施放磨损或消耗了铁斧");
                context.assertTrue(!HumanoidTechniqueHost.isCasting(mob), "收势结束仍残留限速");
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void castSlowsMovementKeepsNavigationPlaysPaAndBlocksMelee(GameTestHelper context) {
        // 起手保留寻路与正数移速，PA 和原版攻击互斥，收势恢复原速。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        mob.setTarget(target);
        mob.getNavigation().moveTo(target, 1);
        double originalSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        var path = mob.getNavigation().getPath();
        start(context, mob, "knock", target);
        context.assertTrue(mob.getNavigation().getPath() == path, "起手丢弃了原移动路径");
        assertPaRequest(context, mob);
        assertMovementSpeed(context, mob, originalSpeed * MOVEMENT_MULTIPLIER);
        Vec3 position = mob.position();
        mob.setNoAi(false /* aiDisabled */);
        mob.setSpeed((float) mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
        mob.travel(new Vec3(0, 0, 1));
        mob.setNoAi(true /* aiDisabled */);
        context.assertTrue(mob.position().distanceToSqr(position) > EPSILON, "限速期间没有实际移动");
        float health = target.getHealth();
        context.assertTrue(!mob.doHurtTarget(context.getLevel(), target) && target.getHealth() == health, "原版近战穿透施放锁");
        context.runAfterDelay(KNOCK_HIT_TICKS + 1, () -> {
            context.assertTrue(mob.swinging, "打击没有触发原版挥手");
            context.assertTrue(target.getHealth() < health, "打击没有实际伤害");
        });
        context.runAfterDelay(RECOVERY_TICKS, () -> {
            context.assertTrue(!HumanoidTechniqueHost.isCasting(mob) && !mob.isAggressive(), "收势后姿势未清理");
            context.assertTrue(MobAnimationServer.snapshot(mob).isEmpty(), "收势后未停止 PA 动作");
            assertMovementSpeed(context, mob, originalSpeed);
            context.succeed();
        });
    }

    private static void assertMovementSpeed(GameTestHelper context, Vindicator mob, double expected) {
        // 实际原版移动属性必须为正，结束时需精确恢复起手前值。
        double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        context.assertTrue(speed > 0 && Math.abs(speed - expected) < EPSILON, "类人移动倍率或清理错误");
    }

    private static void assertPaRequest(GameTestHelper context, Vindicator mob) {
        // 验证正式服务端同步登记，追踪者收到的是专属片段与原始起播时刻；没有专属片段的式用主手攻击片段。
        var requests = MobAnimationServer.snapshot(mob);
        int entityId = mob.getId();
        var active = progress(mob).getActiveCast();
        String expected = switch (active.getRequest().getTechnique().getId()) {
            case PREFIX + "knock" -> "door_breaker_knock";
            case PREFIX + "cleave" -> "door_breaker_cleave";
            case PREFIX + "ram" -> "door_breaker_ram";
            default -> "attack_main";
        };
        boolean requested = requests.stream().anyMatch(request -> {
            var play = request.getPlay();
            return play.getAnimationId().equals(expected)
                    && play.getEntityId() == entityId && play.hasStartTick();
        });
        context.assertTrue(requested, "功法没有下发对应 PA 请求：" + expected);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void allDoorAnimationsUseSelectedHandAndServerStartTick(GameTestHelper context) {
        // 六种出招均从正式功法服务进入 Mobs-Addon 的追踪同步状态。
        LivingEntity target = target(context);
        for (String move : new String[]{"knock", "cleave", "ram"}) {
            for (InteractionHand hand : InteractionHand.values()) {
                Vindicator mob = prepared(context);
                mob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                mob.setItemInHand(hand, new ItemStack(Items.IRON_AXE));
                long startTick = context.getLevel().getGameTime();
                start(context, mob, move, target);
                String suffix = "";
                if (hand == InteractionHand.OFF_HAND) {
                    suffix = "_off";
                }
                String expected = "door_breaker_" + move + suffix;
                var plays = MobAnimationServer.snapshot(mob);
                context.assertTrue(plays.stream().anyMatch(request -> {
                    var play = request.getPlay();
                    return play.getAnimationId().equals(expected) && play.getEntityId() == mob.getId()
                            && play.hasStartTick() && play.getStartTick() == startTick;
                }), "类人未同步正确片段与起点：" + expected + " / " + plays);
                HumanoidTechniqueHost.removed(mob);
                mob.discard();
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = DOOR_ALLOW_BATCH)
    public void cleavesWoodenDoorWhenMobGriefingEnabled(GameTestHelper context) {
        // 世界规则开启时用真实破门式同时移除上下门扇。
        doorCast(context, DoorRule.ALLOW);
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = DOOR_DENY_BATCH)
    public void preservesWoodenDoorWhenMobGriefingDisabled(GameTestHelper context) {
        // 世界规则关闭时仍完成施放，但不得破坏木门。
        doorCast(context, DoorRule.DENY);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void shieldingTargetPrioritizesHook(GameTestHelper context) {
        // 生物目标实际举盾达到原版格挡延迟后，情境选择必须优先破盾。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        target.startUsingItem(InteractionHand.OFF_HAND);
        context.runAfterDelay(SHIELD_READY_TICKS, () -> {
            context.assertTrue(target.isBlocking(), "测试目标未进入真实举盾状态");
            selected(context, mob, target, "hook");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void surroundedPrioritizesSweepAndBystandersDoNotCount(GameTestHelper context) {
        // 近身的同伴与路人都不算围攻；两个以我为目标的近身敌人才触发横扫优先。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        var ally = context.spawn(EntityTypes.PILLAGER, ORIGIN + 1, ORIGIN, ORIGIN);
        ally.setNoAi(true /* aiDisabled */);
        var bystander = context.spawn(EntityTypes.COW, ORIGIN - 1, ORIGIN, ORIGIN + 1);
        bystander.setNoAi(true /* aiDisabled */);
        selected(context, mob, target, "cleave");
        var first = context.spawn(EntityTypes.ZOMBIE, ORIGIN - 1, ORIGIN, ORIGIN);
        var second = context.spawn(EntityTypes.ZOMBIE, ORIGIN + 1, ORIGIN, ORIGIN - 1);
        for (Zombie hunter : new Zombie[]{first, second}) {
            hunter.setNoAi(true /* aiDisabled */);
            hunter.setTarget(mob);
        }
        selected(context, mob, target, "sweep");
        first.discard();
        second.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void pursuedPrioritizesCloseAndPressureAvoidsLongWarmup(GameTestHelper context) {
        // 相对运动识别追赶；远目标与当前受伤分别排除长起手。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        Vec3 away = mob.position().subtract(target.position()).normalize();
        mob.setDeltaMovement(away);
        target.setDeltaMovement(away);
        selected(context, mob, target, "close");
        mob.setDeltaMovement(Vec3.ZERO);
        target.setDeltaMovement(Vec3.ZERO);
        target.setPos(mob.position().add(0, 0, FAR_DISTANCE));
        shortWarmup(context, mob, target);
        target.setPos(mob.position().add(0, 0, 1));
        mob.hurtTime = DECISION_TICKS;
        shortWarmup(context, mob, target);
        context.succeed();
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void finaleOnlySelectedInsideRealFollowUpWindow(GameTestHelper context) {
        // 万门起手必须承接真实破门记录，窗口前拒绝且窗口内缩短起手。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        double originalSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        TechniqueFabric.service().learn(mob.getUUID(), PREFIX + "lower");
        CastOutcome rejected = cast(mob, "finale", target);
        context.assertTrue(rejected.getPhase() != CastPhase.WARMUP, "没有承接记录却施放万门");
        start(context, mob, "cleave", target);
        context.assertTrue(HumanoidBrain.topStrike(mob, target, body(mob)).isEmpty(), "破门起手阶段出现承接候选");
        context.runAfterDelay(FOLLOW_UP_TICKS, () -> {
            selected(context, mob, target, "finale");
            start(context, mob, "finale", target);
            assertMovementSpeed(context, mob, originalSpeed * MOVEMENT_MULTIPLIER);
            var active = progress(mob).getActiveCast();
            long fullWarmup = TechniqueFabric.service().registry().find(reference("finale"))
                    .orElseThrow().getPhases().getWarmupMillis();
            context.assertTrue(active.getWarmupEndMillis() - active.getStartedMillis() < fullWarmup,
                    "承接未缩短万门起手");
            var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, STAGGER_MOVEMENT);
            TechniqueFabric.service().stagger(mob.getUUID(), stagger);
            assertMovementSpeed(context, mob, originalSpeed * STAGGER_MOVEMENT);
            context.assertTrue(!HumanoidTechniqueHost.isCasting(mob), "承接后打断残留施放状态");
            HumanoidTechniqueHost.removed(mob);
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void cleaveWarmupInterruptedOnlyByStaggeringHit(GameTestHelper context) {
        // 与玩家同一伤害入口：轻击低于僵直门槛不打断破门起手，重击打断、减速并拒绝新式。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        Zombie attacker = context.spawn(EntityTypes.ZOMBIE, FLOOR_EDGE, ORIGIN, FLOOR_EDGE);
        attacker.setNoAi(true /* aiDisabled */);
        double originalSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        try {
            start(context, mob, "cleave", target);
            mob.hurt(mob.damageSources().mobAttack(attacker), LIGHT_HIT);
            context.assertTrue(HumanoidTechniqueHost.isCasting(mob), "低于门槛的轻击打断了起手");
            context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(mob.getUUID()) == 0, "轻击产生了僵直");
            mob.invulnerableTime = 0;
            mob.hurt(mob.damageSources().mobAttack(attacker), HEAVY_HIT);
            context.assertTrue(!HumanoidTechniqueHost.isCasting(mob), "僵直重击没有打断起手");
            context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(mob.getUUID()) > 0, "重击没有僵直");
            assertMovementSpeed(context, mob, originalSpeed * STAGGER_MOVEMENT);
            var rejected = cast(mob, "knock", target);
            context.assertTrue(rejected.getFailure() == TechniqueFailure.STAGGERED, "僵直中仍可起式：" + rejected);
        } finally {
            attacker.discard();
            HumanoidTechniqueHost.removed(mob);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void bothArmsExhaustedAvoidsCombatAndRecovers(GameTestHelper context) {
        // 用真实能力曲线模拟两臂虚脱，大脑撤退、禁止原版近战，曲线结束后力量比恢复。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        target.setPos(mob.position().add(0, 0, -1));
        HumanoidTechniqueHost.removed(mob);
        BodyMessages.BodySnapshot exhausted = exhaustedBody(snapshot(mob));
        bind(mob, exhausted);
        mob.setTarget(target);
        mob.setNoAi(false /* aiDisabled */);
        double exhaustedRatio = HumanoidBrain.config().assessment().retreat().armStrengthRatio();
        context.runAfterDelay(DECISION_TICKS, () -> {
            double armRatio = HumanoidFighters.condition(body(mob)).armStrengthRatio();
            context.assertTrue(armRatio < exhaustedRatio, "双臂曲线未降低有效力量：" + armRatio);
            context.assertTrue(HumanoidBrain.blocksMelee(mob) && !mob.doHurtTarget(context.getLevel(), target),
                    "虚脱期间仍可近战");
            context.assertTrue(!progress(mob).hasActiveCast(), "虚脱期间仍开始功法");
            context.assertTrue(!mob.getNavigation().isDone(), "虚脱没有启动撤退寻路；target=" + mob.getTarget()
                    + "; onGround=" + mob.onGround() + "; pos=" + mob.position());
        });
        context.runAfterDelay(KNOCK_HIT_TICKS, () -> {
            double armRatio = HumanoidFighters.condition(body(mob)).armStrengthRatio();
            context.assertTrue(armRatio >= exhaustedRatio, "能力曲线结束后仍判为虚脱：" + armRatio);
            mob.setNoAi(true /* aiDisabled */);
            HumanoidTechniqueHost.removed(mob);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void unloadAndReloadClearsCastWithoutOfflineHit(GameTestHelper context) {
        // 写出仍在起手的 NBT，卸载后恢复旧快照也只能解锁，不能补发伤害。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        float health = target.getHealth();
        start(context, mob, "cleave", target);
        CompoundTag saved = HumanoidEntityNbt.save(mob);
        mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        context.assertTrue(!HumanoidTechniqueHost.isCasting(mob) && !mob.isAggressive(), "卸载未清理施放");
        var restored = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(restored, saved);
        context.getLevel().addFreshEntity(restored);
        HumanoidTechniqueHost.prepare(restored);
        context.assertTrue(!progress(restored).hasActiveCast() && !HumanoidTechniqueHost.isCasting(restored),
                "恢复仍保留过期施放");
        context.assertTrue(restored.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0,
                "恢复没有解除属性锁");
        context.runAfterDelay(RECOVERY_TICKS, () -> {
            context.assertTrue(target.getHealth() == health, "恢复补发了离线伤害");
            context.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void deathDuringWarmupClearsCastPoseAndMovement(GameTestHelper context) {
        // 使用原版死亡回调贯穿权威死体结算和功法恢复，不仅移除测试缓存。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        start(context, mob, "cleave", target);
        float health = target.getHealth();
        mob.die(context.getLevel().damageSources().genericKill());
        context.assertTrue(snapshot(mob).getVitality().getDead(), "死亡未提交到身体");
        context.assertTrue(!progress(mob).hasActiveCast() && !HumanoidTechniqueHost.isCasting(mob),
                "死亡残留施放或锁");
        context.assertTrue(!mob.isAggressive(), "死亡残留举斧姿势");
        context.assertTrue(mob.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0,
                "死亡没有解除属性锁");
        context.runAfterDelay(RECOVERY_TICKS, () -> {
            context.assertTrue(target.getHealth() == health, "死后仍执行功法伤害");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void generatedVindicatorsLearnVolumesFromBrainTable(GameTestHelper context) {
        // 游炁只学上卷；真炁按大脑学卷表逐 UUID 分配，分到下卷与未分到的各验一个。
        Vindicator wandering = generated(context, HumanoidVariant.WANDERING_QI, UUID.randomUUID());
        HumanoidTechniqueHost.prepare(wandering);
        context.assertTrue(progress(wandering).getKnownCount() == UPPER_COUNT, "游炁未只学上卷");
        for (LowerVolume lower : LowerVolume.values()) {
            Vindicator mob = generated(context, HumanoidVariant.TRUE_QI, sample(lower));
            HumanoidTechniqueHost.prepare(mob);
            int expected = lower == LowerVolume.GRANTED ? ALL_COUNT : MIDDLE_COUNT;
            int known = progress(mob).getKnownCount();
            context.assertTrue(known == expected, "真炁学卷与大脑表不符：" + lower + " / " + known);
            var before = progress(mob).getKnownList();
            HumanoidTechniqueHost.prepare(mob);
            context.assertTrue(progress(mob).getKnownList().equals(before), "重复绑定改变了已学卷");
            mob.discard();
        }
        context.succeed();
    }

    private static UUID sample(LowerVolume wanted) {
        // 在随机 UUID 里找一个下卷分配结果符合要求的；表按 UUID 确定性分桶。
        String lower = PREFIX + "lower";
        String entityId = HumanoidSpecies.VINDICATOR.entityId();
        var volumes = HumanoidBrain.config().volumes();
        return Stream.generate(UUID::randomUUID)
                .limit(VOLUME_SAMPLE_LIMIT)
                .filter(id -> volumes.volumes(entityId, HumanoidTier.TRUE_QI, id).contains(lower)
                        == (wanted == LowerVolume.GRANTED))
                .findFirst()
                .orElseThrow(() -> new AssertionError("学卷表找不到下卷分配为 " + wanted + " 的 UUID"));
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void inspectReportsBothHandsAndBodyIdentity(GameTestHelper context) {
        // 字节入口直接检查当前两手物品与唯一身体 ID，不允许另建功法身体。
        Vindicator mob = prepared(context);
        mob.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.WOODEN_SWORD));
        var request = TechniqueHostRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setEntityUuid(mob.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        var response = TechniqueProtocol.decodeHostResponse(new HumanoidTechniqueHost()
                .exchange(TechniqueProtocol.encodeHost(request)));
        context.assertTrue(response.getBody().getId().equals(snapshot(mob).getId()), "宿主返回另一具身体");
        context.assertTrue(response.getHeldItems().getMainHand().getItemId().equals("minecraft:iron_axe"),
                "主手持物未读取实际实体");
        context.assertTrue(response.getHeldItems().getOffHand().getItemId().equals("minecraft:wooden_sword"),
                "副手持物未读取实际实体");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, environment = TECHNIQUE_BATCH)
    public void towardTargetImpulseKeepsHorizontalSpeedAcrossElevationAndReplay(GameTestHelper context) {
        // 限速施放中仍获得足额水平冲量，保留跳跃分量且重试不覆盖之后的速度。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        double originalSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        start(context, mob, "knock", target);
        assertMovementSpeed(context, mob, originalSpeed * MOVEMENT_MULTIPLIER);
        var effects = HumanoidTechniqueHost.prepare(mob).effects();
        target.setPos(mob.position().add(0, 0, TARGET_HORIZONTAL_OFFSET));
        mob.setDeltaMovement(0, VERTICAL_VELOCITY, 0);
        effects.submit(impulse(target));
        Vec3 expected = new Vec3(0, VERTICAL_VELOCITY, IMPULSE_PER_TICK);
        context.assertTrue(mob.getDeltaMovement().distanceTo(expected) < EPSILON, "同高目标冲量方向或速度错误");
        target.setPos(mob.position().add(0, HIGH_TARGET_OFFSET, TARGET_HORIZONTAL_OFFSET));
        mob.setDeltaMovement(0, VERTICAL_VELOCITY, 0);
        var raised = impulse(target);
        effects.submit(raised);
        context.assertTrue(mob.getDeltaMovement().distanceTo(expected) < EPSILON, "错层目标削弱了水平冲量");
        mob.setDeltaMovement(Vec3.ZERO);
        effects.submit(raised);
        context.assertTrue(mob.getDeltaMovement().equals(Vec3.ZERO), "重复冲量请求覆盖了之后的实体速度");
        HumanoidTechniqueHost.removed(mob);
        assertMovementSpeed(context, mob, originalSpeed);
        context.succeed();
    }

    private static TechniqueHost.EffectSubmission impulse(LivingEntity target) {
        // 只替换目标高度，冲量沿用正式协议和宿主执行边界。
        var speed = Quantity.newBuilder().setValue(IMPULSE_SPEED).setUnit(Unit.METERS_PER_SECOND).build();
        var impulse = Impulse.newBuilder().setDirection(ImpulseDirection.TOWARD_TARGET).setSpeed(speed).build();
        String id = UUID.randomUUID().toString();
        var effect = EffectIntent.newBuilder()
                .setCastId(id)
                .setEffectId(id)
                .setTargetId(target.getStringUUID())
                .setImpulse(impulse)
                .build();
        return new TechniqueHost.EffectSubmission(id, effect);
    }

    private static Vindicator prepared(GameTestHelper context) {
        // 真炁身体沿用生成器原始库存，费用兼容必须由正式功法契约保证。
        Vindicator mob = generated(context, HumanoidVariant.TRUE_QI, UUID.randomUUID());
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    private static Vindicator generated(GameTestHelper context, HumanoidVariant variant, UUID id) {
        // 固定测试场地并用正式生成器绑定指定境界，避免随机档位漏测。
        Vindicator mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.COMMAND);
        floor(context);
        mob.setUUID(id);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN)));
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setOnGround(true /* onGround */);
        mob.setPersistenceRequired();
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        context.getLevel().addFreshEntity(mob);
        var region = HumanoidFabric.region(mob);
        var request = new HumanoidBodyGenerator.GenerationRequest(
                HumanoidSpecies.VINDICATOR, variant, id, region.inventory(), HumanoidFabric.now());
        bind(mob, HumanoidFabric.generator().generate(request, context.getLevel().getSeed()));
        return mob;
    }

    private static void floor(GameTestHelper context) {
        // 实际寻路需要连续地面，场地只覆盖本测试附近。
        for (int x = 0; x <= FLOOR_EDGE; x++) {
            for (int z = 0; z <= FLOOR_EDGE; z++) {
                context.setBlock(new BlockPos(x, ORIGIN - 1, z), Blocks.STONE);
            }
        }
    }

    private static LivingEntity target(GameTestHelper context) {
        // 无权威人体的牛保留原版血量，便于观察世界打击而不与敌对 AI 互相伤害。
        Mob target = context.spawn(EntityTypes.COW, ORIGIN, ORIGIN, TARGET_Z);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setSilent(true /* silent */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        return target;
    }

    private static void bind(Vindicator mob, BodyMessages.BodySnapshot snapshot) {
        // 从合法快照恢复唯一 Store，测试不直接改运行中的权威状态。
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        var body = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(), HumanoidSpecies.VINDICATOR);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(body);
    }

    private static BodyMessages.BodySnapshot snapshot(Vindicator mob) {
        // 查询实体已经绑定的唯一权威身体。
        return ((HumanoidEntityAccess) mob).meridian$humanoidBody().snapshot();
    }

    private static BodySnapshot body(Vindicator mob) {
        // 情境选择器读取与功法提交一致的当前身体版本。
        return BodyProtobuf.fromMessage(HumanoidTechniqueHost.prepare(mob).body().snapshot());
    }

    private static TechniqueProgress progress(Vindicator mob) {
        // 不重绑死亡或已卸载实体，直接读取权威快照中的功法进度。
        return TechniqueState.read(BodyProtobuf.fromMessage(snapshot(mob)));
    }

    private static TechniqueReference reference(String name) {
        // 测试引用正式清单身份与版本。
        return TechniqueReference.newBuilder().setId(PREFIX + name).setVersion(1).build();
    }

    private static CastOutcome cast(Vindicator mob, String name, LivingEntity target) {
        // 真实服务负责选手、费用和活动提交，不直接触发世界效果。
        var request = CastRequest.newBuilder()
                .setBodyId(snapshot(mob).getId())
                .setTechnique(reference(name))
                .setTargetId(target.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .build();
        return TechniqueFabric.service().cast(mob.getUUID(), request);
    }

    private static void start(GameTestHelper context, Vindicator mob, String name, LivingEntity target) {
        // 起手拒绝必须显示完整业务回执，不能等到伤害断言才隐式失败。
        var outcome = cast(mob, name, target);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "起手失败：" + outcome);
    }

    private static void selected(GameTestHelper context, Vindicator mob, LivingEntity target, String name) {
        // 直接观察实际世界事实驱动的统一情境评分，取大脑候选里评分最高的一式。
        var selected = HumanoidBrain.topStrike(mob, target, body(mob));
        context.assertTrue(selected.filter(reference(name)::equals).isPresent(),
                "情境未选择 " + name + "：" + selected);
    }

    private static void shortWarmup(GameTestHelper context, Vindicator mob, LivingEntity target) {
        // 距离或受伤不应选出长起手的破门、横扫与无承接的万门。
        var selected = HumanoidBrain.topStrike(mob, target, body(mob));
        context.assertTrue(selected.isPresent(), "压力情境没有可用短起手");
        Set<String> longMoves = Set.of(PREFIX + "cleave", PREFIX + "sweep", PREFIX + "finale");
        context.assertTrue(!longMoves.contains(selected.get().getId()), "压力情境仍选择长起手：" + selected);
    }

    private static void doorCast(GameTestHelper context, DoorRule rule) {
        // 两种规则位于独立测试批次，打击前设置并在断言后恢复世界规则。
        Vindicator mob = prepared(context);
        LivingEntity target = target(context);
        BlockPos lower = new BlockPos(ORIGIN, ORIGIN, ORIGIN + 1);
        var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        context.setBlock(lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        context.setBlock(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        start(context, mob, "cleave", target);
        var world = context.getLevel();
        var rules = world.getGameRules();
        context.runAfterDelay(CLEAVE_HIT_TICKS - 1, () -> {
            boolean previous = rules.get(GameRules.MOB_GRIEFING);
            rules.set(GameRules.MOB_GRIEFING, rule == DoorRule.ALLOW, world.getServer());
            context.runAfterDelay(DECISION_TICKS, () -> {
                try {
                    boolean remains = world.getBlockState(context.absolutePos(lower)).is(Blocks.OAK_DOOR);
                    context.assertTrue(remains == (rule == DoorRule.DENY), "劈门未遵守 mob_griefing：" + rule);
                    context.assertTrue(mob.getMainHandItem().getDamageValue() == 0, "生物劈门磨损铁斧");
                    context.succeed();
                } finally {
                    rules.set(GameRules.MOB_GRIEFING, previous, world.getServer());
                }
            });
        });
    }

    private static BodyMessages.BodySnapshot exhaustedBody(BodyMessages.BodySnapshot body) {
        // 两臂同时施加短期恒定能力下降，结束时间由真实世界身体时钟推进。
        var result = body.toBuilder();
        var start = BodyMessages.CurvePoint.newBuilder().setOffsetMillis(0).setValue(1).build();
        var end = BodyMessages.CurvePoint.newBuilder().setOffsetMillis(EXHAUSTION_MILLIS).setValue(1).build();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (!tissue.getTagsList().contains("human:arm_muscle")) {
                continue;
            }
            var modifier = BodyMessages.CapabilityModifier.newBuilder()
                    .setId("gametest:exhaustion/" + tissue.getId().replace(':', '/'))
                    .setSource("gametest:exhaustion")
                    .setTissueId(tissue.getId())
                    .setStartMillis(body.getSettledAt().getElapsedMillis())
                    .setStrengthPeak(EXHAUSTED_RATIO)
                    .setBurstPeak(EXHAUSTED_RATIO)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .addCurve(start)
                    .addCurve(end)
                    .build();
            result.addModifiers(modifier);
        }
        return result.build();
    }

    private enum DoorRule { ALLOW, DENY }

    /** 学卷用例要找的下卷分配结果。 */
    private enum LowerVolume { GRANTED, WITHHELD }
}
