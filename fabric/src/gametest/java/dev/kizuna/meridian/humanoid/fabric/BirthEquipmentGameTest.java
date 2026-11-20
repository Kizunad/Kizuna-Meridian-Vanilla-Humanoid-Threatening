package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.fabric.BirthVolumes.Equipment;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumePool;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.inventory.MobInventory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;

public final class BirthEquipmentGameTest {
    static final String BOW = "meridian_wayfarer:bow_upper";
    private static final String CROSSBOW = "meridian_wayfarer:crossbow";
    private static final String AXE = "meridian_door_breaker:upper";
    private static final int BODY_SAMPLES = 64;
    private static final int USED_DURABILITY = 11;

    @GameTest
    public void naturalVindicatorCarriesItsDrawnBowBeforeAnyInspection(GameTestHelper context) {
        var original = HumanoidBrain.config();
        var book = new VolumePool.Book("meridian_wayfarer:wayfarer", "minecraft:pillager", 1,
                List.of(BOW), Optional.empty());
        try {
            BirthVolumeGameTest.use(original, new VolumePool(1, 1, 0, List.of(book)));
            for (int index = 0; index < BODY_SAMPLES; index++) {
                var mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.NATURAL);
                mob.setUUID(new UUID(0, index));
                place(context, mob);
                var level = context.getLevel();
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                        EntitySpawnReason.NATURAL, null);
                mob.addTag(HumanoidTier.TAG_PREFIX + HumanoidTier.TRUE_QI.id());
                var originalHand = mob.getMainHandItem().copy();
                context.assertTrue(level.addFreshEntity(mob), "自然出生未入世界");
                var grant = ((HumanoidEntityAccess) mob).meridian$birthVolumes();
                context.assertTrue(grant != null && mob.getTarget() == null, "入世界时尚未配装或已有敌人");
                if (grant.volumes().contains(BOW)) {
                    var pack = MobInventories.inventory(mob);
                    var issued = pack.peek(0);
                    context.assertTrue(grant.equipment() == Equipment.PACKED && issued.is(Items.BOW),
                            "抽中百步的卫道士出生未背弓");
                    context.assertTrue(!issued.isEnchanted() && count(pack) == 1, "多发弹药或附魔");
                    context.assertTrue(ItemStack.matches(originalHand, mob.getMainHandItem()), "配装覆盖了原手");
                    mob.discard();
                    context.succeed();
                    return;
                }
                mob.discard();
            }
            context.fail("固定样本没有能使用百步的真炁身体");
        } finally {
            HumanoidBrain.configure(original);
        }
    }

    @GameTest
    public void removedBowStaysAbsentAfterEntityReload(GameTestHelper context) {
        var mob = pending(context, EntityTypes.VINDICATOR, BOW);
        var axe = new ItemStack(Items.IRON_AXE);
        mob.setItemSlot(EquipmentSlot.MAINHAND, axe);
        enter(context, mob);
        context.assertTrue(mob.getMainHandItem() == axe, "发弓覆盖了卫道士手上的斧");
        var removed = MobInventories.inventory(mob).extract(0, 1);
        context.assertTrue(removed.is(Items.BOW), "缴械前没有弓");
        var loaded = reload(context, mob);
        context.assertTrue(outcome(loaded) == Equipment.PACKED && count(MobInventories.inventory(loaded)) == 0,
                "读档给已缴械的生人补发了弓");
        loaded.discard();
        context.succeed();
    }

    @GameTest
    public void savedPendingGrantIsFulfilledExactlyOnce(GameTestHelper context) {
        var unjoined = pending(context, EntityTypes.VINDICATOR, CROSSBOW);
        var loaded = reload(context, unjoined);
        var again = reload(context, loaded);
        var inventory = MobInventories.inventory(again);
        var crossbow = inventory.peek(0);
        context.assertTrue(crossbow.is(Items.CROSSBOW) && !crossbow.isEnchanted(), "没有发出普通弩");
        context.assertTrue(count(inventory) == 1 && outcome(again) == Equipment.PACKED, "待读档配装丢失或补发");
        again.discard();
        context.succeed();
    }

    @GameTest
    public void fullHandRowOrFullPackDoesNotOverwriteOrRetry(GameTestHelper context) {
        checkFull(context, MobInventory.ROW_SIZE);
        checkFull(context, MobInventory.SIZE);
        context.succeed();
    }

    private static void checkFull(GameTestHelper context, int occupied) {
        var mob = pending(context, EntityTypes.VINDICATOR, BOW);
        var pack = MobInventories.inventory(mob);
        for (int slot = 0; slot < occupied; slot++) {
            pack.insertAt(slot, new ItemStack(Items.EMERALD));
        }
        enter(context, mob);
        context.assertTrue(outcome(mob) == Equipment.NO_ROOM && count(pack) == occupied,
                "手边满时挤掉原物或把兵器发到口袋");
        context.assertTrue(pack.extract(0, 1).is(Items.EMERALD), "第一格原物被覆盖");
        var loaded = reload(context, mob);
        var remaining = MobInventories.inventory(loaded);
        context.assertTrue(outcome(loaded) == Equipment.NO_ROOM && remaining.peek(0).isEmpty(),
                "腾出空格重载后补发");
        context.assertTrue(remaining.snapshot().stream().allMatch(stack -> stack.isEmpty() || stack.is(Items.EMERALD)),
                "满背囊内容被改写");
        loaded.discard();
    }

    @GameTest
    public void missedDrawPreservesBothHandsAndStoredItems(GameTestHelper context) {
        var original = HumanoidBrain.config();
        try {
            BirthVolumeGameTest.use(original, VolumePool.DISABLED);
            var mob = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.STRUCTURE);
            place(context, mob);
            var axe = new ItemStack(Items.IRON_AXE);
            var shield = new ItemStack(Items.SHIELD);
            mob.setItemSlot(EquipmentSlot.MAINHAND, axe);
            mob.setItemSlot(EquipmentSlot.OFFHAND, shield);
            var pack = MobInventories.inventory(mob);
            pack.insertAt(1, new ItemStack(Items.EMERALD));
            enter(context, mob);
            context.assertTrue(outcome(mob) == Equipment.NONE, "零概率仍获得外卷");
            context.assertTrue(mob.getMainHandItem() == axe && mob.getOffhandItem() == shield, "抽空更改了原手持");
            context.assertTrue(count(pack) == 1 && pack.peek(1).is(Items.EMERALD), "抽空更改了背囊");
            mob.discard();
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    @GameTest
    public void existingLoadedCrossbowKeepsComponentsInVanillaInventory(GameTestHelper context) {
        var mob = pending(context, EntityTypes.WANDERING_TRADER, CROSSBOW);
        var crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.setDamageValue(USED_DURABILITY);
        crossbow.set(DataComponents.CUSTOM_NAME, Component.literal("行脚弩"));
        var charged = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.SPECTRAL_ARROW)));
        crossbow.set(DataComponents.CHARGED_PROJECTILES, charged);
        var original = crossbow.copy();
        mob.getInventory().setItem(0, new ItemStack(Items.BREAD));
        mob.getInventory().setItem(1, crossbow);
        enter(context, mob);
        context.assertTrue(mob.getInventory().getItem(1) == crossbow, "配装挪动了原版库存中的弩");
        var loaded = reload(context, mob);
        context.assertTrue(outcome(loaded) == Equipment.ALREADY_CARRIED, "原版库存中的同类武器仍触发配发");
        context.assertTrue(ItemStack.matches(original, loaded.getInventory().getItem(1)), "原弩的组件或位置被改写");
        context.assertTrue(count(MobInventories.inventory(loaded)) == 0, "新发了第二把弩");
        loaded.discard();
        context.succeed();
    }

    @GameTest
    public void offhandAndPocketWeaponsAlreadyMeetTheRequirement(GameTestHelper context) {
        var hand = pending(context, EntityTypes.VINDICATOR, BOW);
        hand.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BOW));
        enter(context, hand);
        context.assertTrue(outcome(hand) == Equipment.ALREADY_CARRIED && count(MobInventories.inventory(hand)) == 0,
                "副手有弓又发了一把");
        hand.discard();
        var pocket = pending(context, EntityTypes.VINDICATOR, BOW);
        var pack = MobInventories.inventory(pocket);
        pack.insertAt(MobInventory.ROW_SIZE, new ItemStack(Items.BOW));
        enter(context, pocket);
        context.assertTrue(outcome(pocket) == Equipment.ALREADY_CARRIED && count(pack) == 1
                && pack.peek(MobInventory.ROW_SIZE).is(Items.BOW), "口袋已有的弓被挪动或补发");
        pocket.discard();
        context.succeed();
    }

    @GameTest
    public void axeMaterialsRespectBothTiersAndConfiguredBoundaries(GameTestHelper context) {
        var original = HumanoidBrain.config();
        try {
            var requirement = BirthWeapon.forVolume(AXE).orElseThrow();
            var id = new UUID(0, 1);
            var pool = original.volumePool();
            var stone = new VolumePool(1, 1, 0, pool.books(), new VolumePool.Materials(0, 0));
            BirthVolumeGameTest.use(original, stone);
            context.assertTrue(requirement.create(id, HumanoidTier.WANDERING_QI, AXE).is(Items.STONE_AXE), "游炁缺石斧");
            context.assertTrue(requirement.create(id, HumanoidTier.TRUE_QI, AXE).is(Items.IRON_AXE), "真炁缺铁斧");
            var iron = new VolumePool(1, 1, 0, pool.books(), new VolumePool.Materials(1, 0));
            BirthVolumeGameTest.use(original, iron);
            context.assertTrue(requirement.create(id, HumanoidTier.WANDERING_QI, AXE).is(Items.IRON_AXE), "游炁缺铁斧");
            var diamond = new VolumePool(1, 1, 0, pool.books(), new VolumePool.Materials(0, 1));
            BirthVolumeGameTest.use(original, diamond);
            var mob = pending(context, EntityTypes.PILLAGER, AXE);
            enter(context, mob);
            var issued = MobInventories.inventory(mob).peek(0);
            context.assertTrue(issued.is(Items.DIAMOND_AXE) && !issued.isEnchanted(), "钻石必中仍未发普通钻石斧");
            mob.discard();
        } finally {
            HumanoidBrain.configure(original);
        }
        context.succeed();
    }

    static <T extends Mob> T pending(GameTestHelper context, EntityType<T> type, String volume) {
        T mob = type.create(context.getLevel(), EntitySpawnReason.EVENT);
        place(context, mob);
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.TRUE_QI);
        var grant = new BirthVolumes(HumanoidTier.TRUE_QI, List.of(volume), Equipment.PENDING);
        ((HumanoidEntityAccess) mob).meridian$birthVolumes(grant);
        return mob;
    }

    static void enter(GameTestHelper context, Mob mob) {
        context.assertTrue(context.getLevel().addFreshEntity(mob), "配装夹具没有加入世界");
    }

    static Equipment outcome(Mob mob) {
        return ((HumanoidEntityAccess) mob).meridian$birthVolumes().equipment();
    }

    private static <T extends Mob> T reload(GameTestHelper context, T mob) {
        var saved = HumanoidEntityNbt.save(mob);
        mob.discard();
        @SuppressWarnings("unchecked")
        var restored = (T) mob.getType().create(context.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(restored, saved);
        enter(context, restored);
        return restored;
    }

    private static void place(GameTestHelper context, Mob mob) {
        mob.setPos(Vec3.atBottomCenterOf(context.absolutePos(BlockPos.ZERO)));
        mob.setNoAi(true /* noAi */);
    }

    private static long count(MobInventory inventory) {
        return inventory.snapshot().stream().filter(stack -> !stack.isEmpty()).mapToInt(ItemStack::getCount).sum();
    }
}
