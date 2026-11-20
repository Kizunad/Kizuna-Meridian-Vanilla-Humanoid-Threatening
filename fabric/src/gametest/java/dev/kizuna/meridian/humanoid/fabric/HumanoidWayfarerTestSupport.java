package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 行矢回归使用真实三档身体、功法目录和原版掠夺者库存。 */
final class HumanoidWayfarerTestSupport {
    static final String STRUCTURE = "meridian_humanoid_test:archery";
    static final String PREFIX = "meridian_wayfarer:";
    static final int ORIGIN = 2;
    private static final double TARGET_HEALTH = 1000;
    private static final int FLOOR_WIDTH = 6;
    private static final int FLOOR_LENGTH = 36;
    private static final Set<String> REQUIRED_HAND_MERIDIANS = Set.of("left_lung_meridian", "right_lung_meridian");
    private static final MeridianOpening OPENED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();

    private HumanoidWayfarerTestSupport() {
        // 只统一实际出生与查询，不提供替代功法宿主。
    }

    static Pillager pillager(GameTestHelper context, HumanoidTier tier) {
        // 加入世界前固定档位；用原版主手弩启动，备用弓走正式出生挂点。
        Pillager mob = EntityTypes.PILLAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        // 行矢专用夹具只比较本系招式，额外功法会改变同一局势的候选和权重。
        ((HumanoidEntityAccess) mob).meridian$skipVolumeBirth();
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(new BlockPos(ORIGIN, ORIGIN, ORIGIN)));
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        mob.setNoAi(true /* aiDisabled */);
        mob.setNoGravity(true /* noGravity */);
        mob.setOnGround(true /* onGround */);
        mob.setPersistenceRequired();
        mob.setLeftHanded(false /* leftHanded */);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "掠夺者没有加入世界");
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    static void floor(GameTestHelper context) {
        // 静止夹具原本悬空；启用真实导航的用例须先铺连续地面。
        for (int x = 1; x <= FLOOR_WIDTH; x++) {
            for (int z = 1; z <= FLOOR_LENGTH; z++) {
                context.setBlock(new BlockPos(x, ORIGIN - 1, z), Blocks.STONE);
            }
        }
    }

    static Cow target(GameTestHelper context, Pillager mob, double distance) {
        // 沿正前方水平放置目标，关闭重力避免静止速度被落体分量污染。
        Cow target = context.spawn(EntityTypes.COW, ORIGIN, ORIGIN, ORIGIN);
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setOnGround(true /* onGround */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HEALTH);
        target.setHealth((float) TARGET_HEALTH);
        target.setPos(mob.position().add(0, 0, distance));
        mob.setTarget(target);
        return target;
    }

    static void prepareQi(GameTestHelper context, Pillager mob) {
        // 随机游炁身体可能只开一条手经；射击回归显式提供连弩需要的两条手经，再填充真元。
        var snapshot = HumanoidTechniqueHost.prepare(mob).body().snapshot().toBuilder();
        for (String meridian : REQUIRED_HAND_MERIDIANS) {
            openMeridian(snapshot, meridian);
        }
        restoreBody(mob, snapshot.build());
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
        FailureCode result = HumanoidHealthHost.debugQi(mob, request);
        context.assertTrue(result == FailureCode.FAILURE_CODE_UNSPECIFIED, "射击夹具真元准备失败：" + result);
        var body = body(mob);
        var definition = definition(mob, "repeater");
        var held = HumanoidWayfarerWeapons.inspect(mob);
        var binding = TechniqueBinder.bind(body, definition, Hand.MAIN_HAND, held.getMainHandSide());
        context.assertTrue(binding.hasBinding(), "射击夹具必须能绑定连弩：" + binding);
        context.assertTrue(TechniqueAvailability.affordable(body, definition, binding.getBinding()),
                "射击夹具必须能支付连弩费用");
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

    static BodySnapshot body(Pillager mob) {
        // 测试读取宿主唯一快照，选式与能力判断使用同一身体。
        return BodyProtobuf.fromMessage(HumanoidTechniqueHost.prepare(mob).body().snapshot());
    }

    static void restoreBody(Pillager mob, BodyMessages.BodySnapshot snapshot) {
        // 通过正式恢复校验绑定唯一身体，不直接改运行中的 Store。
        HumanoidTechniqueHost.removed(mob);
        var saved = new HumanoidBodyState.SavedBody(mob.getUUID(), snapshot.toByteArray(), new byte[0][],
                HumanoidFabric.region(mob).sourceId());
        var restored = HumanoidBodyState.restore(mob.getUUID(), saved, HumanoidFabric.now(), HumanoidSpecies.PILLAGER);
        ((HumanoidEntityAccess) mob).meridian$humanoidBody(restored);
        HumanoidTechniqueHost.prepare(mob);
    }

    static TechniqueDefinition definition(Pillager mob, String id) {
        // 只查询真实已学精确版本，不在测试里重造目录。
        return TechniqueFabric.service().registry().find(reference(mob, id)).orElseThrow();
    }

    static TechniqueReference reference(Pillager mob, String id) {
        // 沿用正式学卷记录中的版本，避免依赖功法库包内构造辅助方法。
        return TechniqueState.read(body(mob)).getKnownList().stream()
                .map(known -> known.getTechnique())
                .filter(known -> known.getId().equals(PREFIX + id))
                .findFirst().orElseThrow();
    }
}
