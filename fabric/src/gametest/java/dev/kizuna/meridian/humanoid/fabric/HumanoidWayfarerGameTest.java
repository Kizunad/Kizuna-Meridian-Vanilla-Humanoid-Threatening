package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
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
        context.assertTrue(mob.getMainHandItem() == exchange.replacement(), "没有交换到原库存弓");
        context.assertTrue(mob.getInventory().getItem(exchange.slot()) == crossbow, "弩没有回原库存格");
        exchange.restore();
        context.assertTrue(mob.getMainHandItem() == crossbow, "拒绝施放后没有还原弩");
        context.assertTrue(crossbow.getDamageValue() == ORIGINAL_DAMAGE, "换兵改变了耐久");
        context.assertTrue(charged.equals(crossbow.get(DataComponents.CHARGED_PROJECTILES)), "换兵改变了上弦内容");
        context.assertTrue(crossbow.get(DataComponents.CUSTOM_NAME).getString().equals("库存测试弩"), "丢失名称组件");
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
        context.assertTrue(mob.getMainHandItem() == arrows, "交换覆盖了另一手弹药");
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
        int bows = 0;
        for (int slot = 0; slot < restored.getInventory().getContainerSize(); slot++) {
            if (restored.getInventory().getItem(slot).is(Items.BOW)) {
                bows++;
            }
        }
        context.assertTrue(bows == 1, "读档丢失或重复补发备用弓");
        context.succeed();
    }
}
