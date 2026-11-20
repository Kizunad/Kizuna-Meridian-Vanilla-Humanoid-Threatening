package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumePool;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.phys.Vec3;

public final class BirthVolumeGameTest {
    private static final String FIST = "meridian_villager:outer";
    private static final String AXE = "meridian_door_breaker:upper";
    private static final VolumePool.Book FIST_BOOK = new VolumePool.Book("meridian_villager:sleeve_fist",
            "minecraft:villager", 1, List.of(FIST), Optional.empty());
    private static final VolumePool ALWAYS_FIST = new VolumePool(1, 1, 0, List.of(FIST_BOOK));

    @GameTest
    public void sevenNativeTablesSurviveTheExtraDraw(GameTestHelper context) {
        var original = HumanoidBrain.config();
        var pool = original.volumePool();
        var certain = new VolumePool(1, 1, 1, pool.books());
        try {
            use(original, certain);
            for (var species : HumanoidSpecies.values()) {
                for (var tier : HumanoidTier.values()) {
                    checkBirth(context, species, tier, certain);
                }
            }
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    private static void checkBirth(GameTestHelper context, HumanoidSpecies species, HumanoidTier tier,
                                   VolumePool pool) {
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(species.entityId()));
        var mob = (Mob) type.create(context.getLevel(), EntitySpawnReason.COMMAND);
        var id = new UUID(species.entityId().hashCode(), tier.ordinal());
        mob.setUUID(id);
        place(context, mob);
        HumanoidSpeciesBirths.birth(mob, tier);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "固定档生人没有入场");
        try {
            var state = HumanoidTechniqueHost.prepare(mob).body();
            var grant = ((HumanoidEntityAccess) mob).meridian$birthVolumes();
            var nativeVolumes = HumanoidBrain.config().volumes().volumes(species.entityId(), tier, id);
            for (var volume : nativeVolumes) {
                context.assertTrue(knows(mob, volume), "缺本系卷：" + volume);
            }
            var side = mob.getMainArm() == HumanoidArm.LEFT ? HandSide.LEFT : HandSide.RIGHT;
            var available = pool.books().stream().filter(book -> !book.nativeSpecies().equals(species.entityId()))
                    .filter(book -> book.upper().stream().anyMatch(upper -> BirthVolumes.usable(state, side, upper)))
                    .toList();
            System.out.printf("实际出生池 %s %s 合用书=%d 外卷=%s%n", species, tier, available.size(), grant.volumes());
            if (tier == HumanoidTier.MORTAL || available.isEmpty()) {
                context.assertTrue(grant.volumes().isEmpty(), "凡人或空池仍抽到外卷");
            } else {
                context.assertTrue(!grant.volumes().isEmpty(), "必抽配置漏授");
                String chosen = grant.volumes().getFirst();
                context.assertTrue(available.stream().anyMatch(book -> book.upper().contains(chosen)), "抽到不适用卷");
                grant.volumes().forEach(volume -> context.assertTrue(knows(mob, volume), "外卷没有学全：" + volume));
            }
        } finally {
            mob.discard();
        }
    }

    @GameTest
    public void guaranteedForeignVolumePreservesTheOriginalBirthSnapshot(GameTestHelper context) {
        var original = HumanoidBrain.config();
        var mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.SPAWN_ITEM_USE);
        var tier = HumanoidTier.WANDERING_QI;
        mob.setUUID(new UUID(0, 1));
        place(context, mob);
        try {
            use(original, ALWAYS_FIST);
            HumanoidSpeciesBirths.birth(mob, tier);
            var access = (HumanoidEntityAccess) mob;
            context.assertTrue(access.meridian$birthVolumes().volumes().equals(List.of(FIST)), "出生未抽到外家");
            var actual = access.meridian$humanoidBody().snapshot();
            var subject = new HumanoidBodyState.BirthSubject(HumanoidSpecies.VINDICATOR, mob.getUUID(), tier);
            var expected = HumanoidBodyState.born(subject, context.getLevel().getSeed(),
                    HumanoidFabric.region(mob), actual.getSettledAt());
            context.assertTrue(expected.snapshot().equals(actual), "抽外卷改变了原始出生身体快照");
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    @GameTest
    public void savedEmptyDrawCannotLearnNewPoolOnRebind(GameTestHelper context) {
        var original = HumanoidBrain.config();
        try {
            use(original, VolumePool.DISABLED);
            var first = born(context);
            var saved = HumanoidEntityNbt.save(first);
            first.discard();
            use(original, ALWAYS_FIST);
            var restored = restore(context, saved);
            var grant = ((HumanoidEntityAccess) restored).meridian$birthVolumes();
            context.assertTrue(grant.volumes().isEmpty() && grant.equipment() == BirthVolumes.Equipment.NONE,
                    "读档改变了出生抽空记录");
            context.assertFalse(knows(restored, FIST), "出生抽空后重新绑定竟学了外卷");
            context.assertTrue(knows(restored, AXE), "重绑丢失了本系卷");
            restored.discard();
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    @GameTest
    public void savedSelectionIncludesTheEquipmentDecision(GameTestHelper context) {
        var original = HumanoidBrain.config();
        try {
            use(original, ALWAYS_FIST);
            var first = born(context);
            var expected = new BirthVolumes(HumanoidTier.WANDERING_QI, List.of(FIST), BirthVolumes.Equipment.PENDING);
            context.assertTrue(expected.equals(((HumanoidEntityAccess) first).meridian$birthVolumes()), "出生未选外家");
            context.assertTrue(knows(first, AXE) && knows(first, FIST), "同一身体缺本系或外来卷");
            context.assertTrue(first.getMainHandItem().isEmpty(), "外卷授予修改了原有空手");
            var saved = HumanoidEntityNbt.save(first);
            first.discard();
            use(original, VolumePool.DISABLED);
            var restored = restore(context, saved);
            context.assertTrue(expected.equals(((HumanoidEntityAccess) restored).meridian$birthVolumes()),
                    "新配置改变了已存卷或配装状态");
            context.assertTrue(knows(restored, FIST), "保存的卷没有学全");
            restored.discard();
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    @GameTest
    public void legacyUnboundAndBoundEntitiesNeverRoll(GameTestHelper context) {
        var original = HumanoidBrain.config();
        try {
            use(original, ALWAYS_FIST);
            var unbound = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.LOAD);
            var old = HumanoidEntityNbt.save(unbound);
            checkLegacy(context, old);
            var fresh = born(context);
            var saved = HumanoidEntityNbt.save(fresh);
            fresh.discard();
            saved.getCompound("meridian_humanoid").orElseThrow().remove("volume_grant");
            checkLegacy(context, saved);
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    @GameTest
    public void newUnboundEntityRetainsEligibilityAcrossUnload(GameTestHelper context) {
        var original = HumanoidBrain.config();
        try {
            use(original, ALWAYS_FIST);
            var unbound = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.STRUCTURE);
            unbound.addTag(HumanoidTier.TAG_PREFIX + HumanoidTier.WANDERING_QI.id());
            var restored = restore(context, HumanoidEntityNbt.save(unbound));
            var grant = ((HumanoidEntityAccess) restored).meridian$birthVolumes();
            context.assertTrue(grant != null && grant.volumes().equals(List.of(FIST)), "新生未建身实体漏抽");
            context.assertTrue(knows(restored, FIST), "惰性入口未授外卷");
            restored.discard();
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    private static void checkLegacy(GameTestHelper context, CompoundTag nbt) {
        var restored = restore(context, nbt);
        context.assertTrue(((HumanoidEntityAccess) restored).meridian$birthVolumes() == null, "旧实体补抽了外卷");
        restored.discard();
    }

    private static Vindicator born(GameTestHelper context) {
        var mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.EVENT);
        place(context, mob);
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.WANDERING_QI);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "出生实体未入世界");
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    private static Vindicator restore(GameTestHelper context, CompoundTag nbt) {
        var mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(mob, nbt);
        place(context, mob);
        context.assertTrue(context.getLevel().addFreshEntity(mob), "恢复实体未入世界");
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    private static void place(GameTestHelper context, Mob mob) {
        var position = Vec3.atBottomCenterOf(context.absolutePos(BlockPos.ZERO));
        mob.setPos(position);
    }

    private static boolean knows(Mob mob, String volume) {
        var state = HumanoidTechniqueHost.prepare(mob).body().currentBody();
        var known = TechniqueState.read(state).getKnownList().stream().map(entry -> entry.getTechnique()).toList();
        var registry = TechniqueFabric.service().registry();
        return known.containsAll(registry.findVolume(volume).orElseThrow().getTechniquesList());
    }

    private static void use(HumanoidBrainConfig original, VolumePool pool) {
        var config = new HumanoidBrainConfig(original.schedule(), original.threat(), original.assessment(),
                original.response(), original.volumes(), original.concurrency(), original.villager(), pool);
        HumanoidBrain.configure(config);
    }
}
