package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegKnockbackKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeFacing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 生物宿主执行拳掌打击：和玩家同一份 Human 执行器，带撩天的上掀。 */
public final class HumanoidLimbStrikeGameTest {
    private static final int TIMEOUT = 100;
    private static final int FLOOR_EDGE = 6;
    private static final BlockPos VILLAGER_POS = new BlockPos(2, 2, 2);
    private static final BlockPos TARGET_POS = new BlockPos(2, 2, 4);
    private static final double TARGET_HEALTH = 1000;
    private static final double STRIKE_DISTANCE = 2.5;
    private static final double STRIKE_ANGLE = 60;
    private static final double STRIKE_MULTIPLIER = 1.5;
    private static final double BASE_DAMAGE = 1.5;
    private static final double KNOCKBACK = 0.3;
    private static final double LIFT_METERS_PER_SECOND = 8;
    private static final double TICKS_PER_SECOND = 20;
    private static final double SPEED_TOLERANCE = 1e-6;
    private static final String DAMAGE_TYPE = "meridian_humanoid_test:palm_lift";

    @GameTest(maxTicks = TIMEOUT)
    public void villagerLandsLimbStrikeAndLiftsTheTarget(GameTestHelper context) {
        // 村民的效果宿主收到一记双臂上掀的拳掌打击：身前 2 格的牛掉血，并按声明的速度被掀起来。
        // 接 Human 的执行器以前，生物宿主直接拒收拳掌打击，上掀也不认。
        floor(context);
        Villager villager = EntityTypes.VILLAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(VILLAGER_POS));
        villager.snapTo(position.x, position.y, position.z, 0, 0);
        villager.setNoAi(true /* aiDisabled */);
        villager.setOnGround(true /* onGround */);
        HumanoidSpeciesBirths.birth(villager, HumanoidTier.TRUE_QI);
        context.assertTrue(context.getLevel().addFreshEntity(villager), "村民未加入世界");
        Cow target = context.spawn(EntityTypes.COW, TARGET_POS);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        try {
            var submission = new TechniqueHost.EffectSubmission(UUID.randomUUID().toString(), liftingStrike());
            HumanoidTechniqueHost.prepare(villager).effects().submit(submission);
            context.assertTrue(target.getHealth() < TARGET_HEALTH, "村民的拳掌打击没有造成伤害");
            double lifted = target.getDeltaMovement().y;
            context.assertTrue(Math.abs(lifted - LIFT_METERS_PER_SECOND / TICKS_PER_SECOND) < SPEED_TOLERANCE,
                    "上掀速度不是声明的每秒 8 米：每 tick " + lifted);
        } finally {
            target.discard();
            villager.discard();
        }
        context.succeed();
    }

    private static EffectIntent liftingStrike() {
        // 填全肢击校验要的字段：双臂、上掀；伤害类型用测试数据包里自带的一种，不认具体功法包。
        String castId = UUID.randomUUID().toString();
        LimbStrike strike = LimbStrike.newBuilder()
                .setShape(StrikeShape.SINGLE_TARGET)
                .setFacing(StrikeFacing.STRIKE_FORWARD)
                .setDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setAngle(quantity(STRIKE_ANGLE, Unit.DEGREE))
                .setMultiplier(quantity(STRIKE_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(1)
                .setCastTargetLimit(1)
                .setArm(ArmSelection.BOTH_ARMS)
                .setDamageTypeId(DAMAGE_TYPE)
                .setKnockbackKind(LegKnockbackKind.LIFT)
                .setKnockback(quantity(KNOCKBACK, Unit.RATIO))
                .setLiftSpeed(quantity(LIFT_METERS_PER_SECOND, Unit.METERS_PER_SECOND))
                .setKnockbackWhenBlocked(true)
                .setLastStrike(true)
                .setDedupGroup("limb_lift")
                .build();
        ArmRules rules = ArmRules.newBuilder()
                .setIgnoreHeldItems(true)
                .setRequireGrounded(true)
                .setBaseDamage(quantity(BASE_DAMAGE, Unit.DAMAGE_POINT))
                .build();
        LimbStrikeIntent intent = LimbStrikeIntent.newBuilder().setStrike(strike).setArmRules(rules).build();
        return EffectIntent.newBuilder().setCastId(castId).setEffectId(castId).setLimbStrike(intent).build();
    }

    private static Quantity quantity(double value, Unit unit) {
        return Quantity.newBuilder().setValue(value).setUnit(unit).build();
    }

    private static void floor(GameTestHelper context) {
        // 连续地面让着地事实和打击几何稳定。
        for (int x = 0; x <= FLOOR_EDGE; x++) {
            for (int z = 0; z <= FLOOR_EDGE; z++) {
                context.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }
}
