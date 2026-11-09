package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 夹具只准备真实身体和空间，决策、施放、药瓶与残药走正式入口。 */
final class WitchTestSupport {
    static final String STRUCTURE = HumanoidWayfarerTestSupport.STRUCTURE;
    static final int TIMEOUT = 300;
    static final int ANOINT_EFFECT = 54;
    static final int ANOINT_END = 84;
    static final int IMBIBE_EFFECT = 66;
    static final int IMBIBE_END = 104;
    static final int THROW_EFFECT = 22;
    static final int THROW_END = 46;
    static final int FAN_EFFECT = 18;
    static final int FAN_END = 38;
    static final int MIST_EFFECT = 34;
    static final int MIST_END = 54;
    static final double NEAR = 3;
    static final double FAR = 8;
    static final double INJURED = 0.5;
    static final double DRINK_INJURED = 0.65;
    private static final double QI = 100;
    private static final int ORIGIN = 2;

    private WitchTestSupport() {
        // 测试之间不共享实体、静态选择结果或药效状态。
    }

    static Witch witch(GameTestHelper context, HumanoidTier tier) {
        // 所有用例以正式出生入口学卷；生产开脉保证也在这里受检。
        HumanoidWayfarerTestSupport.floor(context);
        Witch witch = spawn(context, EntityTypes.WITCH, tier);
        witch.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        if (tier != HumanoidTier.MORTAL) {
            var qi = DebugQi.newBuilder()
                    .setMode(DebugQiMode.SET_INVENTORY)
                    .setAmount(QI)
                    .setDensity(1)
                    .build();
            context.assertTrue(HumanoidHealthHost.debugQi(witch, qi) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                    "女巫真元准备失败");
        }
        return witch;
    }

    static <T extends Mob> T spawn(GameTestHelper context, EntityType<T> type, HumanoidTier tier) {
        // 固定空间与原版随机种子，关闭寻路仅用于精确核对施放边界。
        T mob = type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 point = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN)));
        mob.snapTo(point.x, point.y, point.z, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setNoGravity(true /* noGravity */);
        mob.setOnGround(true /* onGround */);
        mob.getRandom().setSeed(0);
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "测试生物未加入世界");
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    static Villager enemy(GameTestHelper context, Witch witch, double distance) {
        // 真实有身体的凡人敌人，既能接药效也能留下部位残药。
        Villager enemy = spawn(context, EntityTypes.VILLAGER, HumanoidTier.MORTAL);
        enemy.setPos(witch.position().add(0, 0, distance));
        witch.setTarget(enemy);
        return enemy;
    }

    static BodySnapshot body(Mob mob) {
        // 每次从宿主结清并读取唯一权威身体。
        return HumanoidTechniqueHost.prepare(mob).body().snapshot();
    }

    static void injury(Mob mob, double arms, double legs) {
        // 通过正式恢复校验准备部位伤势，不伪造选式情境或药效结果。
        var snapshot = body(mob).toBuilder();
        for (var state : snapshot.getPartStatesBuilderList()) {
            String id = state.getId();
            double ratio = 1;
            if (id.contains("arm") || id.contains("hand")) {
                ratio = arms;
            } else if (id.contains("thigh") || id.contains("leg") || id.contains("foot")) {
                ratio = legs;
            }
            double maximum = snapshot.getStructure().getPartsList().stream()
                    .filter(part -> part.getId().equals(id)).findFirst().orElseThrow().getMaxHp();
            state.setHp(maximum * ratio);
        }
        restore(mob, snapshot.build());
    }

    static void restore(Mob mob, BodySnapshot snapshot) {
        // 复用生产 NBT 恢复入口，确保结构、部位状态和版本都通过完整校验。
        HumanoidTechniqueHost.removed(mob);
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        var species = HumanoidSpecies.fromEntityId(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())
                .orElseThrow();
        var restored = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(), species);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(restored);
        HumanoidTechniqueHost.prepare(mob);
    }

    static void choose(GameTestHelper context, Witch witch, String id, BrewKind kind) {
        // 直接执行正式女巫决策，断言实际起手与真实主手，不只检查选择器返回值。
        HumanoidWitchAi.decide(witch);
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(body(witch)));
        context.assertTrue(progress.hasActiveCast(), "女巫没有起手 " + id);
        context.assertTrue(progress.getActiveCast().getRequest().getTechnique().getId().equals(id),
                "选式不符：" + progress.getActiveCast().getRequest().getTechnique().getId());
        context.assertTrue(BrewItemStacks.isBrew(witch.getMainHandItem())
                && BrewItemStacks.kind(witch.getMainHandItem()) == kind, "主手药种不符");
    }

    static CastRequest request(Witch witch, String id) {
        // 指定式的生命周期测试仍通过正式服务校验学会、费用、持物和效果。
        var reference = TechniqueReference.newBuilder()
                .setId(id)
                .setVersion(1)
                .build();
        return CastRequest.newBuilder()
                .setBodyId(body(witch).getId())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
    }

    static void finish(GameTestHelper context, Mob... mobs) {
        // 真实卸载同时释放身体、临时主手、残药与大脑状态。
        for (Mob mob : mobs) {
            mob.discard();
        }
        context.succeed();
    }
}
