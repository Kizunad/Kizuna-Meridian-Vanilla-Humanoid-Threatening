package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;

/** 流浪商人腿法宿主回归：移动事实、学卷和完整 Damage ID 均走正式接线。 */
public final class HumanoidPeddlerGameTest {
    private static final int ORIGIN_X = 2;
    private static final int ORIGIN_Y = 2;
    private static final int ORIGIN_Z = 2;
    private static final int TARGET_Z = 4;
    private static final int FLOOR_EDGE = 6;
    private static final double TARGET_HEALTH = 1000;
    private static final double STRIKE_DISTANCE = 2.5;
    private static final double STRIKE_ANGLE = 60;
    private static final double STRIKE_MULTIPLIER = 1.0;
    private static final double BASE_DAMAGE = 2.0;
    private static final double ARMOR_DAMAGE = 0.5;
    private static final double NO_KNOCKBACK = 0;
    private static final String ROAD_TECHNIQUE = "meridian_peddler:snap_kick";
    private static final String DAMAGE_TYPE = "meridian_peddler:kick_lead_legs";

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
