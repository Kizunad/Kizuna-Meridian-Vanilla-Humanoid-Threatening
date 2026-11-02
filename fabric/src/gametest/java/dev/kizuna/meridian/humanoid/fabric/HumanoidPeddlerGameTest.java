package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
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
    private static final double EXHAUSTED_RATIO = 0.5;
    private static final long EXHAUSTION_MILLIS = 60_000;

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
        trader.setTarget(hunter);
        trader.setNoAi(false /* aiDisabled */);
        var goals = new GoalSelector();
        var targets = new GoalSelector();
        int interval = HumanoidBrain.config().schedule().decisionIntervalTicks();
        for (int tick = 0; tick < interval; tick++) {
            trader.tickCount++;
            HumanoidBrain.tick(trader, goals, targets);
        }
        var decisions = HumanoidBrain.decisions(trader.getUUID());
        context.assertTrue(!decisions.isEmpty()
                        && decisions.get(decisions.size() - 1).decision().action()
                        == dev.kizuna.meridian.humanoid.server.Decision.Action.RETREAT,
                "虚脱腿法没有只跑：" + decisions);
        context.assertTrue(!TechniqueState.read(BodyProtobuf.fromMessage(snapshot(trader))).hasActiveCast(),
                "虚脱腿法仍开始施放");
        trader.setNoAi(true /* aiDisabled */);
        hunter.discard();
        HumanoidTechniqueHost.removed(trader);
        context.succeed();
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

    private static void floor(GameTestHelper context) {
        // 连续地面让实体的着地事实和视线几何稳定。
        for (int x = 0; x <= FLOOR_EDGE; x++) {
            for (int z = 0; z <= FLOOR_EDGE; z++) {
                context.setBlock(new BlockPos(x, ORIGIN_Y - 1, z), Blocks.STONE);
            }
        }
    }
}
