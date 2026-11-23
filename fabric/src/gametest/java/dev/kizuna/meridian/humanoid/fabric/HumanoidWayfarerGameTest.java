package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

import static dev.kizuna.meridian.humanoid.fabric.HumanoidGameTestTicks.DEFAULT_TIMEOUT;
import static dev.kizuna.meridian.humanoid.fabric.HumanoidWayfarerTestSupport.*;

/** 掠夺者行矢出生、双手事实、库存交换与原版存档回归。 */
public final class HumanoidWayfarerGameTest {
    private static final int ORIGINAL_DAMAGE = 7;
    private static final int PILLAGER_INVENTORY_SIZE = 5;

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void tiersLearnTheirVolumesAndOnlyTrueQiReceivesBackupBow(GameTestHelper context) {
        // 三档都走正式出生和自动学卷，不以随机概率替代边界断言。
        for (HumanoidTier tier : HumanoidTier.values()) {
            Pillager mob = pillager(context, tier);
            var progress = TechniqueState.read(body(mob));
            var known = progress.getKnownList().stream().map(entry -> entry.getTechnique().getId()).toList();
            boolean cultivator = tier != HumanoidTier.MORTAL;
            boolean trueQi = tier == HumanoidTier.TRUE_QI;
            context.assertTrue(known.contains(PREFIX + "repeater") == cultivator, "弩卷分配档位错误：" + tier);
            context.assertTrue(known.contains(PREFIX + "full_draw") == trueQi, "弓上卷分配档位错误：" + tier);
            context.assertTrue(HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).isPresent()
                    == trueQi, "备用弓分配档位错误：" + tier);
            context.assertTrue(mob.getInventory().getContainerSize() == PILLAGER_INVENTORY_SIZE, "不是原版五格库存");
            mob.discard();
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void previewSwapAndRollbackPreserveLoadedCrossbowAndComponents(GameTestHelper context) {
        // 预览不改装备；交换与失败还原保留原实例、耐久、自定义名称和原上弦内容。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        ItemStack crossbow = mob.getMainHandItem();
        crossbow.setDamageValue(ORIGINAL_DAMAGE);
        crossbow.set(DataComponents.CUSTOM_NAME, Component.literal("库存测试弩"));
        var charged = ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.SPECTRAL_ARROW)));
        crossbow.set(DataComponents.CHARGED_PROJECTILES, charged);
        var exchange = HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow();
        var preview = exchange.preview(HumanoidWayfarerWeapons.inspect(mob));
        context.assertTrue(preview.getMainHandProjectileWeapon() == ProjectileWeaponCategory.BOW, "预览没有弓");
        context.assertTrue(mob.getMainHandItem() == crossbow, "枚举候选时已经换兵");
        exchange.apply();
        context.assertTrue(ItemStack.isSameItemSameComponents(mob.getMainHandItem(), exchange.replacement()),
                "没有交换到背囊弓");
        context.assertTrue(ItemStack.isSameItemSameComponents(
                MobInventories.inventory(mob).peek(exchange.slot()), crossbow), "弩没有回原背囊格");
        exchange.restore();
        context.assertTrue(ItemStack.isSameItemSameComponents(mob.getMainHandItem(), crossbow),
                "拒绝施放后没有还原弩");
        var restoredCrossbow = mob.getMainHandItem();
        context.assertTrue(restoredCrossbow.getDamageValue() == ORIGINAL_DAMAGE, "换兵改变了耐久");
        context.assertTrue(charged.equals(restoredCrossbow.get(DataComponents.CHARGED_PROJECTILES)),
                "换兵改变了上弦内容");
        context.assertTrue(restoredCrossbow.get(DataComponents.CUSTOM_NAME).getString().equals("库存测试弩"),
                "丢失名称组件");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void rollbackRestoresBirthWeaponMarker(GameTestHelper context) {
        // 出生兵器换到手上施放后，收势必须把掉率标记带回原背囊槽。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        var inventory = MobInventories.inventory(mob);
        inventory.extract(0, 1);
        inventory.insertBirthWeapon(new ItemStack(Items.BOW));
        var exchange = HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow();
        context.assertTrue(exchange.birthWeapon(), "备用弓没有保留出生兵器标记");
        exchange.apply();
        context.assertTrue(!inventory.isBirthWeapon(exchange.slot()),
                "换到手上的出生兵器仍占用背囊标记");
        exchange.restore();
        context.assertTrue(inventory.isBirthWeapon(exchange.slot()), "还手后没有恢复出生兵器标记");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void dyingRestoresChangedBirthWeaponBeforeDrops(GameTestHelper context) {
        // 死亡掉落前把施放中已损耗的出生弓还回背囊并恢复其掉率标记。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        ItemStack originalCrossbow = mob.getMainHandItem().copy();
        var inventory = MobInventories.inventory(mob);
        inventory.extract(0, 1);
        inventory.insertBirthWeapon(new ItemStack(Items.BOW));
        var exchange = HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow();
        exchange.apply();
        ItemStack wornBow = mob.getMainHandItem();
        wornBow.setDamageValue(1);
        HumanoidTechniqueHost.recordExchange(mob, exchange);
        HumanoidTechniqueHost.dying(mob);
        ItemStack restoredBow = inventory.peek(exchange.slot());
        context.assertTrue(ItemStack.isSameItemSameComponents(mob.getMainHandItem(), originalCrossbow),
                "死亡前没有恢复原弩");
        context.assertTrue(restoredBow.getDamageValue() == 1, "还手丢失了弓的耐久变化");
        context.assertTrue(inventory.isBirthWeapon(exchange.slot()), "死亡前没有恢复出生兵器掉率标记");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void offHandSwapKeepsAmmunitionInMainHand(GameTestHelper context) {
        // 副手持弩时换兵仍替换副手，另一只手的箭不会被塞入库存或删除。
        Pillager mob = pillager(context, HumanoidTier.TRUE_QI);
        ItemStack crossbow = mob.getMainHandItem();
        ItemStack arrows = new ItemStack(Items.ARROW);
        mob.setItemSlot(EquipmentSlot.OFFHAND, crossbow);
        mob.setItemSlot(EquipmentSlot.MAINHAND, arrows);
        var exchange = HumanoidWayfarerWeapons.reserve(mob, HumanoidWayfarerWeapons.BOWS).orElseThrow();
        context.assertTrue(exchange.hand() == EquipmentSlot.OFFHAND, "副手武器被换到主手");
        exchange.apply();
        var items = HumanoidWayfarerWeapons.inspect(mob);
        context.assertTrue(items.hasMainHandProjectileWeapon() && items.hasOffHandProjectileWeapon(), "漏报双手类别");
        context.assertTrue(items.getOffHandProjectileWeapon() == ProjectileWeaponCategory.BOW, "副手没有弓");
        context.assertTrue(items.getCanFindAmmunition(), "生物无限弹药事实缺失");
        context.assertTrue(ItemStack.isSameItemSameComponents(mob.getMainHandItem(), arrows),
                "交换覆盖了另一手弹药");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT, structure = STRUCTURE)
    public void savedInventoryRetainsBackupWithoutReseeding(GameTestHelper context) {
        // 原版库存 NBT 负责持久化；读档重建会话不补发第二把弓。
        Pillager original = pillager(context, HumanoidTier.TRUE_QI);
        var saved = HumanoidEntityNbt.save(original);
        original.discard();
        Pillager restored = EntityTypes.PILLAGER.create(context.getLevel(), EntitySpawnReason.LOAD);
        HumanoidEntityNbt.load(restored, saved);
        context.assertTrue(context.getLevel().addFreshEntity(restored), "读档实体没有加入世界");
        HumanoidTechniqueHost.prepare(restored);
        long bows = MobInventories.inventory(restored).snapshot().stream()
                .filter(stack -> stack.is(Items.BOW)).count();
        context.assertTrue(bows == 1, "读档丢失或重复补发备用弓");
        context.succeed();
    }
}
