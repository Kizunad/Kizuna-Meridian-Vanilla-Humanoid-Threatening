package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegKnockbackKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeFacing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 流浪商人腿法宿主回归：移动事实、学卷和完整 Damage ID 均走正式接线。 */
public final class HumanoidPeddlerGameTest {
    private static final int ORIGIN_X = 2;
    private static final int ORIGIN_Y = 2;
    private static final int ORIGIN_Z = 2;
    private static final int TARGET_Z = 4;
    private static final int FLOOR_EDGE = 6;
    private static final int BEHIND_Z = 0;
    private static final int FAR_TARGET_Z = 6;
    // 离 FAR_TARGET_Z 的追兵 5 格：在轻步 4 到 24 格的距离门槛里，又没到撤退的安全距离。
    private static final int STEP_AWAY_Z = 1;
    private static final String LIGHT_STEP = "meridian_basic_techniques:light_step";
    private static final int HUNTER_X_LEFT = 1;
    private static final int HUNTER_X_RIGHT = 3;
    private static final int HUNTER_Z = 2;
    private static final double TARGET_HEALTH = 1000;
    private static final double STRIKE_DISTANCE = 2.5;
    private static final double STRIKE_ANGLE = 60;
    private static final double STRIKE_MULTIPLIER = 1.0;
    private static final double BASE_DAMAGE = 2.0;
    private static final double ARMOR_DAMAGE = 0.5;
    private static final double NO_KNOCKBACK = 0;
    private static final String ROAD_TECHNIQUE = "meridian_peddler:snap_kick";
    private static final String DAMAGE_TYPE = "meridian_peddler:kick_lead_legs";
    private static final String MULE_KICK = "meridian_peddler:mule_kick";
    private static final String GROUND_SWEEP = "meridian_peddler:ground_sweep";
    private static final String PUSH_KICK = "meridian_peddler:push_kick";
    private static final long SPENT_COOLDOWN_MILLIS = 60_000;
    private static final int CLOSE_BEHIND_Z = 1;
    private static final int TRUE_QI_X = 5;
    private static final String FOOT_MERIDIAN = "left_stomach_meridian";
    private static final int ROOM_HEIGHT = 2;
    /** 一格宽的窄道外壳：内部 x 1..5、z 1。 */
    private static final BoundingBox LANE = new BoundingBox(0, ORIGIN_Y - 1, 0, 6, ORIGIN_Y + ROOM_HEIGHT, 2);
    private static final int LANE_X = 3;
    private static final int LANE_Z = 1;
    private static final int HUNTER_LANE_Z = 4;
    /** 5×5 石室外壳：内部 x、z 都是 ROOM_NEAR..ROOM_FAR。 */
    private static final BoundingBox ROOM = new BoundingBox(0, ORIGIN_Y - 1, 0, 6, ORIGIN_Y + ROOM_HEIGHT, 6);
    private static final int ROOM_NEAR = 1;
    private static final int ROOM_FAR = 5;
    private static final double NO_SAFE_DISTANCE = 0;
    private static final MeridianOpening OPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();
    private static final double EXHAUSTED_RATIO = 0.5;
    // 千里腿目录 ai_hints.combat.minimum_leg_force：出腿门槛（牛）。
    private static final double LEG_FORCE_GATE = 3000;
    private static final double INJURED_LEG_RATIO = 0.6;
    private static final Set<String> FRONT_KICKS = Set.of(ROAD_TECHNIQUE, PUSH_KICK);
    private static final long EXHAUSTION_MILLIS = 60_000;
    private static final double TRIP_UPWARD_PER_TICK = 0.1;
    private static final double SPEED_TOLERANCE = 1e-6;
    private static final List<Double> TRIP_INITIAL_VERTICAL_SPEEDS = List.of(-0.4, 0.0, 0.6);

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void wanderingTraderInspectsAndExecutesDeclaredLegStrike(GameTestHelper context) {
        // 生物宿主回传着地事实，自动学到上卷，并直接解析千里腿命名空间的伤害类型。
        floor(context);
        WanderingTrader trader = context.spawn(EntityTypes.WANDERING_TRADER, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        trader.setNoAi(true /* aiDisabled */);
        trader.setOnGround(true /* onGround */);
        trader.setYRot(0);
        trader.setYHeadRot(0);
        trader.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        HumanoidTechniqueHost.Session session = HumanoidTechniqueHost.prepare(trader);
        assertLearnedRoad(context, trader, session.body().snapshot());
        assertInspectFacts(context, trader);

        Cow target = context.spawn(EntityTypes.COW, ORIGIN_X, ORIGIN_Y, TARGET_Z);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setSilent(true /* silent */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        int bootDamage = trader.getItemBySlot(EquipmentSlot.FEET).getDamageValue();
        double health = target.getHealth();
        session.effects().submit(new TechniqueHost.EffectSubmission(UUID.randomUUID().toString(), legStrikeEffect()));
        context.assertTrue(target.getHealth() < health, "流浪商人腿击没有造成伤害");
        context.assertTrue(trader.getItemBySlot(EquipmentSlot.FEET).getDamageValue() == bootDamage,
                "生物腿击错误扣除了靴子耐久");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void traderTripClearsHorizontalMotionAndLiftsFallingOrJumpingTargets(GameTestHelper context) {
        // 通过商人真实效果宿主验证绊倒，避免只测试速度计算而漏掉世界执行接线。
        for (double initialVertical : TRIP_INITIAL_VERTICAL_SPEEDS) {
            WanderingTrader actor = trader(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
            Cow victim = target(context, ORIGIN_X, TARGET_Z);
            try {
                victim.setDeltaMovement(1, initialVertical, 1);
                double initialHealth = victim.getHealth();
                EffectIntent base = legStrikeEffect();
                var strike = base.getLegStrike().getStrike().toBuilder()
                        .setKnockbackKind(LegKnockbackKind.TRIP)
                        .setKnockback(quantity(1, Unit.RATIO))
                        .build();
                var intent = base.getLegStrike().toBuilder().setStrike(strike).build();
                EffectIntent effect = base.toBuilder().setLegStrike(intent).build();
                var submission = new TechniqueHost.EffectSubmission(UUID.randomUUID().toString(), effect);
                HumanoidTechniqueHost.prepare(actor).effects().submit(submission);
                context.assertTrue(victim.getHealth() < initialHealth, "绊倒未通过真实伤害入口");
                Vec3 velocity = victim.getDeltaMovement();
                context.assertTrue(velocity.x == 0 && velocity.z == 0, "绊倒未清空水平速度");
                context.assertTrue(Math.abs(velocity.y - TRIP_UPWARD_PER_TICK) < SPEED_TOLERANCE,
                        "绊倒未将竖直速度设置为每秒两米");
            } finally {
                victim.discard();
                actor.discard();
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void missedKickDoesNotBreakNextStrike(GameTestHelper context) {
        // 商人原版没有攻击力属性；空场腿击仍须完成结算，随后可以正常命中。
        floor(context);
        WanderingTrader trader = trader(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
        var session = HumanoidTechniqueHost.prepare(trader);
        try {
            session.effects().submit(new TechniqueHost.EffectSubmission(
                    UUID.randomUUID().toString(), legStrikeEffect()));
            Cow target = target(context, ORIGIN_X, TARGET_Z);
            double health = target.getHealth();
            session.effects().submit(new TechniqueHost.EffectSubmission(
                    UUID.randomUUID().toString(), legStrikeEffect()));
            context.assertTrue(target.getHealth() < health, "挥空后下一次腿击未造成伤害");
            target.discard();
            context.succeed();
        } finally {
            trader.discard();
        }
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pursuerBehindChoosesMuleKick(GameTestHelper context) {
        // 身后近距离追兵进入同一份情境选择，尥蹶优先于主动腿法。
        WanderingTrader trader = trader(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
        Cow target = target(context, ORIGIN_X, BEHIND_Z);
        assertSelected(context, trader, target, MULE_KICK);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void surroundedPeddlerChoosesGroundSweep(GameTestHelper context) {
        // 两个三格内追兵才算被围，扫堂候选由 Framework 的 surrounded 权重取出。
        WanderingTrader trader = trader(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
        Cow target = target(context, ORIGIN_X, TARGET_Z);
        Zombie left = hunter(context, trader, HUNTER_X_LEFT, HUNTER_Z);
        Zombie right = hunter(context, trader, HUNTER_X_RIGHT, HUNTER_Z);
        assertSelected(context, trader, target, GROUND_SWEEP);
        left.discard();
        right.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void mortalAndWanderingPeddlersDoNotChooseActiveKicks(GameTestHelper context) {
        // 凡人没有可绑定腿法，游炁在非防守距离也只保留逃跑，不主动抽鞭腿或连环。
        for (HumanoidTier tier : new HumanoidTier[]{HumanoidTier.MORTAL, HumanoidTier.WANDERING_QI}) {
            WanderingTrader trader = trader(context, tier, ORIGIN_X, ORIGIN_Z);
            Cow target = target(context, ORIGIN_X, FAR_TARGET_Z);
            var selected = HumanoidBrain.topStrike(trader, target, body(trader));
            context.assertTrue(selected.isEmpty() || !selected.get().getId().equals("meridian_peddler:roundhouse"),
                    tier + " 不应主动鞭腿：" + selected);
            context.assertTrue(selected.isEmpty() || !selected.get().getId().equals("meridian_peddler:triple_kick"),
                    tier + " 不应主动连环：" + selected);
            trader.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void exhaustedLegsOnlyChooseRetreat(GameTestHelper context) {
        // 两腿有效力量低于目录门槛时，真实大脑记录撤退且不建立腿法施放。
        WanderingTrader trader = trader(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
        HumanoidTechniqueHost.Session session = HumanoidTechniqueHost.prepare(trader);
        BodyMessages.BodySnapshot exhausted = exhaustedLegs(session.body().snapshot());
        HumanoidTechniqueHost.removed(trader);
        bind(trader, exhausted);
        HumanoidTechniqueHost.prepare(trader);
        Zombie hunter = hunter(context, trader, ORIGIN_X, TARGET_Z);
        // 关着 AI 的实体不会自己着地，而大脑只在着地时找退路；着地后撤退要经过退路搜索。
        trader.setOnGround(true /* onGround */);
        var decisions = decideOnce(trader, hunter);
        context.assertTrue(!decisions.isEmpty()
                        && decisions.get(decisions.size() - 1).decision().action() == Decision.Action.RETREAT,
                "虚脱腿法没有只跑：" + decisions);
        context.assertTrue(!TechniqueState.read(BodyProtobuf.fromMessage(snapshot(trader))).hasActiveCast(),
                "虚脱腿法仍开始施放");
        trader.setNoAi(true /* aiDisabled */);
        hunter.discard();
        HumanoidTechniqueHost.removed(trader);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void injuredLegsStillKickWhileTiredLegsDoNot(GameTestHelper context) {
        // 两腿带伤、有效力量掉到出腿门槛以下，歇多久也回不来的真炁商人：腿没累，照样算出得了防守腿法，
        // 对手在身前近处时弹腿或正蹬在候选里；同样的伤再加上两腿虚脱才算腿力不够。伤只写进身体副本喂给读候选。
        WanderingTrader trader = kicker(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
        Cow front = target(context, ORIGIN_X, TARGET_Z);
        var injuredSnapshot = injuredLegs(snapshot(trader));
        BodySnapshot injured = BodyProtobuf.fromMessage(injuredSnapshot);
        double effective = HumanoidFighters.legForceNewtons(injured);
        double unhurt = HumanoidFighters.unhurtLegForceNewtons(injured);
        context.assertTrue(effective < LEG_FORCE_GATE && unhurt >= LEG_FORCE_GATE,
                "夹具应是伤到有效腿力不过门槛、不计伤势却过门槛：" + effective + " / " + unhurt);
        var readout = HumanoidRepertoires.read(trader, injured, opponent(front), technique -> false);
        context.assertTrue(readout.means().contains(Fighter.Means.CASTABLE_DEFENSE)
                        && !readout.means().contains(Fighter.Means.WEAK_LEGS),
                "腿伤了但没累，却算出不了防守腿法：" + readout.means());
        context.assertTrue(readout.strikes().stream()
                        .anyMatch(known -> FRONT_KICKS.contains(known.getTechnique().getId())),
                "伤腿商人对身前近处的对手没有弹腿、正蹬候选：" + readout.strikes());
        BodySnapshot tired = BodyProtobuf.fromMessage(exhaustedLegs(injuredSnapshot));
        var tiredReadout = HumanoidRepertoires.read(trader, tired, opponent(front), technique -> false);
        context.assertTrue(tiredReadout.means().contains(Fighter.Means.WEAK_LEGS)
                        && !tiredReadout.means().contains(Fighter.Means.CASTABLE_DEFENSE)
                        && tiredReadout.strikes().isEmpty(),
                "腿又伤又累仍算出得了腿：" + tiredReadout.means() + " / " + tiredReadout.strikes());
        trader.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void weakLegsWalkAwayInsteadOfLightStepping(GameTestHelper context) {
        // 腿力没过出腿门槛的真炁商人被追、退路还在：轻步明明放得了，也不放轻步、不跃步，只走开等腿力回来。
        WanderingTrader trader = kicker(context, HumanoidTier.TRUE_QI, ORIGIN_X, STEP_AWAY_Z);
        exhaust(trader);
        Zombie hunter = hunter(context, trader, ORIGIN_X, FAR_TARGET_Z);
        // 关着 AI 的实体不会自己着地，而大脑只在着地时找退路。
        trader.setOnGround(true /* onGround */);
        var readout = HumanoidRepertoires.read(trader, body(trader), opponent(hunter), technique -> false);
        var means = readout.means();
        context.assertTrue(means.contains(Fighter.Means.WEAK_LEGS), "夹具应是腿力不够出腿：" + means);
        boolean stepReady = readout.repertoire().supports().stream()
                .anyMatch(support -> support.technique().equals(LIGHT_STEP));
        context.assertTrue(stepReady, "夹具里轻步应放得了：" + readout.repertoire().supports());
        var decisions = decideOnce(trader, hunter);
        context.assertTrue(!decisions.isEmpty(), "大脑没有做出决策");
        var decision = decisions.getLast().decision();
        context.assertTrue(decision.action() == Decision.Action.RETREAT && decision.technique().isEmpty(),
                "腿力不够时没有只走开：" + decisions);
        context.assertTrue(!TechniqueState.read(body(trader)).hasActiveCast(), "腿力不够时仍开始施放");
        trader.setNoAi(true /* aiDisabled */);
        hunter.discard();
        HumanoidTechniqueHost.removed(trader);
        trader.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unreadyDefenderSidestepsInsteadOfWaiting(GameTestHelper context) {
        // 一格宽的封闭窄道里没有比现在离追兵更远的点；腿力不够出防守腿法的商人不原地转身等，
        // 贴墙横着让开到不比现在更近的点。追兵隔着墙，只提供敌意登记。
        WanderingTrader trader = unreadyTrader(context, LANE_X, LANE_Z);
        enclose(context, LANE);
        Zombie hunter = hunter(context, trader, LANE_X, HUNTER_LANE_Z);
        // 关着 AI 的实体不走物理、不会自己着地；寻路器和找退路都要求站在地上。
        trader.setOnGround(true /* onGround */);
        assertAwaitingDefense(context, trader, hunter);
        var staying = HumanoidMotion.Directive.STAYING;
        context.assertTrue(HumanoidMotion.retreatPoint(trader, hunter, staying, NO_SAFE_DISTANCE).isEmpty(),
                "窄道里不该有短退点");
        context.assertTrue(HumanoidMotion.sidestepPoint(trader, hunter, staying).isPresent(), "窄道里应能横着让开");
        var decisions = decideOnce(trader, hunter);
        context.assertTrue(!decisions.isEmpty(), "大脑没有做出决策");
        var movement = decisions.get(decisions.size() - 1).decision().movement();
        context.assertTrue(movement == Decision.Movement.RETREAT || movement == Decision.Movement.KITE,
                "腿法没就绪时没有横着让开：" + decisions);
        trader.setNoAi(true /* aiDisabled */);
        hunter.discard();
        HumanoidTechniqueHost.removed(trader);
        trader.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void unreadyDefenderSwitchesCornerWhileFoeIsFar(GameTestHelper context) {
        // 5×5 封闭石室，商人贴在一个角里，追兵站在对角：没有比现在更远、也没有不比现在更近的点。
        // 腿力不够出防守腿法、对手又还远时，商人不在角里转身等，沿墙去相邻的两个角之一。
        WanderingTrader trader = unreadyTrader(context, ROOM_NEAR, ROOM_NEAR);
        enclose(context, ROOM);
        double pressed = ROOM_NEAR + trader.getBbWidth() / 2;
        Vec3 corner = context.absoluteVec(new Vec3(pressed, ORIGIN_Y, pressed));
        trader.snapTo(corner.x, corner.y, corner.z, 0, 0);
        Zombie hunter = hunter(context, trader, ROOM_FAR, ROOM_FAR);
        // 关着 AI 的实体不会自己着地，而大脑只在着地时找退路。
        trader.setOnGround(true /* onGround */);
        assertAwaitingDefense(context, trader, hunter);
        var staying = HumanoidMotion.Directive.STAYING;
        context.assertTrue(HumanoidMotion.sidestepPoint(trader, hunter, staying).isEmpty(), "墙角里不该有横让点");
        var decisions = decideOnce(trader, hunter);
        context.assertTrue(!decisions.isEmpty(), "大脑没有做出决策");
        var movement = decisions.getLast().decision().movement();
        context.assertTrue(movement == Decision.Movement.RETREAT || movement == Decision.Movement.KITE,
                "对手还远时没有沿墙换角：" + decisions);
        Vec3 eastCorner = blockCenter(context, ROOM_FAR, ROOM_NEAR);
        Vec3 southCorner = blockCenter(context, ROOM_NEAR, ROOM_FAR);
        Set<Vec3> neighbours = Set.of(eastCorner, southCorner);
        var destination = HumanoidBrain.directive(trader.getUUID()).flatMap(HumanoidMotion.Directive::destination);
        context.assertTrue(destination.filter(neighbours::contains).isPresent(), "没有沿墙去相邻的角：" + destination);
        trader.setNoAi(true /* aiDisabled */);
        hunter.discard();
        HumanoidTechniqueHost.removed(trader);
        trader.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void spentLegDefensesLeaveNoCastableDefense(GameTestHelper context) {
        // 弹腿、正蹬、尥蹶都在冷却时不算出得了防守腿法，商人照腿力不够的办法躲开拉距离；
        // 没被围时扫堂冷却已过也不算。冷却只写进身体副本喂给读候选，不写回宿主。
        WanderingTrader trader = kicker(context, HumanoidTier.TRUE_QI, ORIGIN_X, ORIGIN_Z);
        context.assertTrue(castableDefense(trader, body(trader), Optional.empty()), "冷却已过的真炁商人出不了防守腿法");
        var spent = spentDefenses(snapshot(trader), List.of(ROAD_TECHNIQUE, PUSH_KICK, MULE_KICK));
        context.assertTrue(!castableDefense(trader, spent, Optional.empty()), "防守腿法都在冷却仍算出得了腿");
        trader.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void closeOpponentBehindLeavesOnlyMuleKick(GameTestHelper context) {
        // 对手贴在身后、转身期间就会挨打时只有尥蹶算数：不会尥蹶的游炁商人照腿力不够的办法躲，
        // 会尥蹶的真炁商人照样算出得了腿；对手在身前时正面两式算数。
        WanderingTrader wandering = kicker(context, HumanoidTier.WANDERING_QI, ORIGIN_X, ORIGIN_Z);
        Cow behind = target(context, ORIGIN_X, CLOSE_BEHIND_Z);
        Cow front = target(context, ORIGIN_X, TARGET_Z);
        BodySnapshot wanderingBody = body(wandering);
        context.assertTrue(!castableDefense(wandering, wanderingBody, opponent(behind)),
                "对手贴在身后、不会尥蹶仍算出得了腿");
        context.assertTrue(castableDefense(wandering, wanderingBody, opponent(front)), "对手在身前却出不了防守腿法");
        WanderingTrader trueQi = kicker(context, HumanoidTier.TRUE_QI, TRUE_QI_X, ORIGIN_Z);
        Cow trueQiBehind = target(context, TRUE_QI_X, CLOSE_BEHIND_Z);
        context.assertTrue(castableDefense(trueQi, body(trueQi), opponent(trueQiBehind)),
                "会尥蹶的商人对身后贴身对手出不了腿");
        wandering.discard();
        trueQi.discard();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pinnedPeddlerCountsOnlySweepAndMuleKick(GameTestHelper context) {
        // 被两个追兵夹住时正面两式出不了：不会尥蹶的游炁商人扫堂冷却已过才算出得了腿，扫堂在冷却就不算。
        WanderingTrader trader = kicker(context, HumanoidTier.WANDERING_QI, ORIGIN_X, ORIGIN_Z);
        Cow front = target(context, ORIGIN_X, TARGET_Z);
        Zombie left = hunter(context, trader, HUNTER_X_LEFT, HUNTER_Z);
        Zombie right = hunter(context, trader, HUNTER_X_RIGHT, HUNTER_Z);
        context.assertTrue(castableDefense(trader, body(trader), opponent(front)),
                "被夹住、扫堂冷却已过却不算出得了腿");
        var swept = spentDefenses(snapshot(trader), List.of(GROUND_SWEEP));
        context.assertTrue(!castableDefense(trader, swept, opponent(front)), "被夹住、扫堂在冷却仍算出得了腿");
        left.discard();
        right.discard();
        trader.discard();
        context.succeed();
    }

    private static List<HumanoidMind.Logged> decideOnce(WanderingTrader trader, Zombie hunter) {
        // 打开 AI，按决策间隔推进真实大脑，正好做出一次决策。
        trader.setTarget(hunter);
        trader.setNoAi(false /* aiDisabled */);
        var goals = new GoalSelector();
        var targets = new GoalSelector();
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        for (int tick = 0; tick < interval; tick++) {
            trader.tickCount++;
            HumanoidBrain.tick(trader, goals, targets);
        }
        return HumanoidBrain.decisions(trader.getUUID());
    }

    private static boolean castableDefense(WanderingTrader trader, BodySnapshot body,
                                           Optional<HumanoidOpponent> opponent) {
        // 与大脑同一份读候选。
        return HumanoidRepertoires.read(trader, body, opponent, technique -> false)
                .means().contains(Fighter.Means.CASTABLE_DEFENSE);
    }

    private static Optional<HumanoidOpponent> opponent(LivingEntity target) {
        // 测试对手不带施放观察。
        return Optional.of(new HumanoidOpponent(target, Optional.empty()));
    }

    private static BodySnapshot spentDefenses(BodyMessages.BodySnapshot body, List<String> techniques) {
        // 把给定几式的冷却设到一分钟后，其余不动。
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(body)).toBuilder();
        long until = body.getSettledAt().getElapsedMillis() + SPENT_COOLDOWN_MILLIS;
        for (String technique : techniques) {
            progress.putCooldowns(technique, until);
        }
        var spentProgress = progress.build();
        var spent = body.toBuilder()
                .putAddonProgress(TechniqueState.KEY, TechniqueState.pack(spentProgress))
                .build();
        return BodyProtobuf.fromMessage(spent);
    }

    private static EffectIntent legStrikeEffect() {
        // 效果意图完整填写腿击校验所需字段，伤害类型保持目录声明的完整 ID。
        String castId = UUID.randomUUID().toString();
        LegStrike strike = LegStrike.newBuilder()
                .setShape(StrikeShape.SINGLE_TARGET)
                .setFacing(StrikeFacing.STRIKE_FORWARD)
                .setDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setAngle(quantity(STRIKE_ANGLE, Unit.DEGREE))
                .setMultiplier(quantity(STRIKE_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(1)
                .setCastTargetLimit(1)
                .setLeg(LegSelection.FRONT_LEG)
                .setDamageTypeId(DAMAGE_TYPE)
                .setKnockbackKind(LegKnockbackKind.NONE)
                .setKnockback(quantity(NO_KNOCKBACK, Unit.RATIO))
                .setKnockbackWhenBlocked(true)
                .setLastStrike(true)
                .setDedupGroup("m6_leg")
                .build();
        LegRules rules = LegRules.newBuilder()
                .setIgnoreHeldItems(true)
                .setRequireGrounded(true)
                .setBaseDamage(quantity(BASE_DAMAGE, Unit.DAMAGE_POINT))
                .setArmorDamage(quantity(ARMOR_DAMAGE, Unit.DAMAGE_POINT))
                .setDurabilityCost(1)
                .build();
        LegStrikeIntent intent = LegStrikeIntent.newBuilder()
                .setStrike(strike)
                .setLegRules(rules)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId(castId)
                .setLegStrike(intent)
                .build();
    }

    private static void assertLearnedRoad(GameTestHelper context, WanderingTrader trader,
                                          BodyMessages.BodySnapshot snapshot) {
        // 学卷由正式大脑表分配，准备同一会话不会重复绑定或另建身体。
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(snapshot));
        context.assertTrue(progress.getKnownList().stream()
                        .anyMatch(known -> known.getTechnique().getId().equals(ROAD_TECHNIQUE)),
                "流浪商人没有学到千里腿上卷");
        context.assertTrue(HumanoidTechniqueHost.hasSession(trader), "流浪商人没有建立身体会话");
    }

    private static WanderingTrader trader(GameTestHelper context, HumanoidTier tier, int x, int z) {
        // 在加入世界前固定出生档位，避免测试依赖随机卷表分配。
        floor(context);
        WanderingTrader trader = EntityTypes.WANDERING_TRADER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        BlockPos relative = new BlockPos(x, ORIGIN_Y, z);
        var position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        trader.snapTo(position.x, position.y, position.z, 0, 0);
        trader.setPersistenceRequired();
        trader.setNoAi(true /* aiDisabled */);
        HumanoidSpeciesBirths.birth(trader, tier);
        context.assertTrue(context.getLevel().addFreshEntity(trader), "流浪商人未加入世界");
        HumanoidTechniqueHost.prepare(trader);
        return trader;
    }

    private static WanderingTrader kicker(GameTestHelper context, HumanoidTier tier, int x, int z) {
        // 随机出生的身体偶尔一条足经都没开，防守腿法全绑不上；腿法只要一条足经，夹具开一条再填满真元。
        WanderingTrader trader = trader(context, tier, x, z);
        var opened = snapshot(trader).toBuilder();
        openMeridian(opened, FOOT_MERIDIAN);
        HumanoidTechniqueHost.removed(trader);
        bind(trader, opened.build());
        HumanoidTechniqueHost.prepare(trader);
        DebugQi fill = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
        FailureCode result = HumanoidHealthHost.debugQi(trader, fill);
        context.assertTrue(result == FailureCode.FAILURE_CODE_UNSPECIFIED, "腿法夹具真元准备失败：" + result);
        boolean footOpened = snapshot(trader).getMeridianStatesList().stream()
                .anyMatch(state -> state.getId().equals(FOOT_MERIDIAN)
                        && state.getOpening().getStage() == MeridianOpeningStage.OPENED);
        context.assertTrue(footOpened, "腿法夹具没有开出足经");
        return trader;
    }

    private static void openMeridian(BodyMessages.BodySnapshot.Builder snapshot, String id) {
        // 与人体开脉内容采用相同的容量增量，已开经脉不重复扩容；通过正式恢复入口校验。
        var state = snapshot.getMeridianStatesBuilderList().stream()
                .filter(meridian -> meridian.getId().equals(id))
                .findFirst().orElseThrow();
        if (state.getOpening().getStage() == MeridianOpeningStage.OPENED) {
            return;
        }
        var definition = snapshot.getStructureBuilder().getMeridiansBuilderList().stream()
                .filter(meridian -> meridian.getId().equals(id))
                .findFirst().orElseThrow();
        definition.setLumenVolume(definition.getLumenVolume() + HumanOpening.LUMEN_VOLUME_GAIN);
        state.setOpening(OPENED);
    }

    private static Cow target(GameTestHelper context, int x, int z) {
        // 目标保持静止并明确着地，避免目标事实干扰处境用例。
        Cow target = context.spawn(EntityTypes.COW, x, ORIGIN_Y, z);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setOnGround(true /* onGround */);
        return target;
    }

    private static Zombie hunter(GameTestHelper context, WanderingTrader trader, int x, int z) {
        // 追兵只提供以商人为目标的敌意登记，不运行自己的原版 AI。
        Zombie hunter = context.spawn(EntityTypes.ZOMBIE, x, ORIGIN_Y, z);
        hunter.setNoAi(true /* aiDisabled */);
        hunter.setTarget(trader);
        return hunter;
    }

    private static void assertSelected(GameTestHelper context, WanderingTrader trader, LivingEntity target,
                                       String expected) {
        // 统一读取正式身体快照，避免测试另建选择上下文。
        var currentBody = body(trader);
        var selected = HumanoidBrain.topStrike(trader, target, currentBody);
        context.assertTrue(selected.isPresent() && selected.get().getId().equals(expected),
                "千里腿情境未选择 " + expected + "：" + selected);
    }

    private static BodySnapshot body(WanderingTrader trader) {
        // 读取宿主唯一身体，供大脑评分使用。
        return BodyProtobuf.fromMessage(snapshot(trader));
    }

    private static BodyMessages.BodySnapshot snapshot(WanderingTrader trader) {
        // 查询实体当前权威快照。
        return ((HumanoidEntityAccess) trader).meridian$humanoidBody().snapshot();
    }

    private static BodyMessages.BodySnapshot exhaustedLegs(BodyMessages.BodySnapshot body) {
        // 对六块腿肌肉施加短期恒定出力下降，模拟两腿虚脱。
        var result = body.toBuilder();
        var start = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(0)
                .setValue(1)
                .build();
        var end = BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(EXHAUSTION_MILLIS)
                .setValue(1)
                .build();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (!tissue.getTagsList().contains("human:leg_muscle")) {
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

    private static BodyMessages.BodySnapshot injuredLegs(BodyMessages.BodySnapshot body) {
        // 腿肌所在的部位都只剩 INJURED_LEG_RATIO 的 HP：伤势压住两腿发力，疲劳一点没有。
        var legParts = body.getStructure().getTissuesList().stream()
                .filter(tissue -> tissue.getTagsList().contains(HumanoidFighters.LEG_MUSCLE))
                .map(tissue -> tissue.getPartId())
                .collect(Collectors.toSet());
        var result = body.toBuilder();
        for (var state : result.getPartStatesBuilderList()) {
            if (legParts.contains(state.getId())) {
                state.setHp(state.getHp() * INJURED_LEG_RATIO);
            }
        }
        return result.build();
    }

    private static void bind(WanderingTrader trader, BodyMessages.BodySnapshot snapshot) {
        // 从合法快照恢复唯一身体 Store，测试不直接改运行中的对象。
        var saved = new HumanoidBodyState.SavedBody(trader.getUUID(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(trader).sourceId());
        var body = HumanoidBodyState.restore(trader.getUUID(), saved, HumanoidFabric.now(),
                HumanoidSpecies.WANDERING_TRADER);
        ((HumanoidEntityAccess) trader).meridian$humanoidBody(body);
    }

    private static void assertInspectFacts(GameTestHelper context, WanderingTrader trader) {
        String requestId = UUID.randomUUID().toString();
        TechniqueHostRequest request = TechniqueHostRequest.newBuilder()
                .setRequestId(requestId)
                .setEntityUuid(trader.getStringUUID())
                .setInspect(true /* inspect */)
                .build();
        var response = TechniqueProtocol.decodeHostResponse(new HumanoidTechniqueHost()
                .exchange(TechniqueProtocol.encodeHost(request)));
        context.assertTrue(response.hasInspectFacts(), "inspect 缺少宿主事实");
        context.assertTrue(response.getInspectFacts().hasGrounded(), "inspect 缺少着地事实");
        context.assertTrue(response.getInspectFacts().getGrounded(), "流浪商人应处于着地状态");
        context.assertTrue(response.getInspectFacts().hasInWater()
                        && response.getInspectFacts().hasClimbing()
                        && response.getInspectFacts().hasRiding()
                        && response.getInspectFacts().hasGliding(),
                "inspect 缺少移动状态事实");
    }

    private static Quantity quantity(double value, Unit unit) {
        // 所有腿击数值通过带单位的协议量传递，避免宿主猜测单位。
        return Quantity.newBuilder()
                .setValue(value)
                .setUnit(unit)
                .build();
    }

    private static void enclose(GameTestHelper context, BoundingBox shell) {
        // 外壳的四壁、地面和顶棚都是石头，只留严格在内的空气；商人走不出去，外壳外的点都不可达。
        for (int x = shell.minX(); x <= shell.maxX(); x++) {
            for (int y = shell.minY(); y <= shell.maxY(); y++) {
                for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
                    boolean inside = x > shell.minX() && x < shell.maxX() && z > shell.minZ() && z < shell.maxZ()
                            && y > shell.minY() && y < shell.maxY();
                    if (!inside) {
                        context.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
    }

    private static WanderingTrader unreadyTrader(GameTestHelper context, int x, int z) {
        // 两腿虚脱的真炁商人：学会了防守腿法，腿力出不了腿。
        WanderingTrader trader = trader(context, HumanoidTier.TRUE_QI, x, z);
        exhaust(trader);
        return trader;
    }

    private static void exhaust(WanderingTrader trader) {
        // 按当前快照加上两腿虚脱，再从这份快照恢复身体并重新装上功法会话。
        BodyMessages.BodySnapshot exhausted = exhaustedLegs(snapshot(trader));
        HumanoidTechniqueHost.removed(trader);
        bind(trader, exhausted);
        HumanoidTechniqueHost.prepare(trader);
    }

    private static void assertAwaitingDefense(GameTestHelper context, WanderingTrader trader, Zombie hunter) {
        var means = HumanoidRepertoires.read(trader, body(trader), opponent(hunter), technique -> false).means();
        context.assertTrue(means.contains(Fighter.Means.KNOWN_DEFENSE)
                        && !means.contains(Fighter.Means.CASTABLE_DEFENSE),
                "夹具应是学会了防守腿法却出不了腿：" + means);
    }

    private static Vec3 blockCenter(GameTestHelper context, int x, int z) {
        return Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(x, ORIGIN_Y, z)));
    }

    private static void floor(GameTestHelper context) {
        // 连续地面让实体的着地事实和视线几何稳定。
        for (int x = 0; x <= FLOOR_EDGE; x++) {
            for (int z = 0; z <= FLOOR_EDGE; z++) {
                context.setBlock(new BlockPos(x, ORIGIN_Y - 1, z), Blocks.STONE);
            }
        }
    }
}
