package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.human.fabric.HumanProjectileItems;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 掠夺者使用原版五格库存存放备用武器，换兵保留物品的全部组件。 */
final class HumanoidWayfarerWeapons {
    static final ProjectileWeaponCategory BOWS = ProjectileWeaponCategory.BOW;
    static final ProjectileWeaponCategory CROSSBOWS = ProjectileWeaponCategory.CROSSBOW;

    private HumanoidWayfarerWeapons() {
        // 库存由原版实体保存，不另建持久状态。
    }

    static void equipAtBirth(LivingEntity entity, HumanoidTier tier) {
        // 只在新身体出生时补一把弓，读档不会重复补发，也不覆盖原版库存物品。
        if (!(entity instanceof Pillager pillager) || tier != HumanoidTier.TRUE_QI
                || holds(pillager, BOWS) || reserve(pillager, BOWS).isPresent()) {
            return;
        }
        var inventory = pillager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.BOW));
                return;
            }
        }
    }

    static boolean holds(LivingEntity entity, ProjectileWeaponCategory weapon) {
        // 候选和真实发箭使用同一类别判定；另一手限制由书级规则判定。
        return HumanProjectileItems.category(entity.getMainHandItem()) == weapon
                || HumanProjectileItems.category(entity.getOffhandItem()) == weapon;
    }

    static HeldItems inspect(LivingEntity entity) {
        // 生物远程攻击有无限普通箭；两手类别仍从同次查询的实际物品读取。
        return HumanProjectileItems.inspect(entity);
    }

    static Set<ProjectileWeaponCategory> reserves(LivingEntity entity) {
        // 备用武器仅来自真实库存，不把正在持有的武器重复计入。
        var result = EnumSet.noneOf(ProjectileWeaponCategory.class);
        if (reserve(entity, BOWS).isPresent()) {
            result.add(ProjectileWeaponCategory.BOW);
        }
        if (reserve(entity, CROSSBOWS).isPresent()) {
            result.add(ProjectileWeaponCategory.CROSSBOW);
        }
        return Set.copyOf(result);
    }

    static Optional<Exchange> forTechnique(LivingEntity entity, TechniqueDefinition definition) {
        // 当前已持所需类别时不换手；否则只为明确的弓弩式预览对应库存。
        if (!definition.hasProjectileWeapon()) {
            return Optional.empty();
        }
        ProjectileWeaponCategory required = definition.getProjectileWeapon();
        if (holds(entity, required)) {
            return Optional.empty();
        }
        return reserve(entity, required);
    }

    static Optional<Exchange> reserve(LivingEntity entity, ProjectileWeaponCategory weapon) {
        // 只预览库存，不在候选枚举期间改变装备；原来副手持武器时仍用副手换兵。
        if (!(entity instanceof Pillager pillager)) {
            return Optional.empty();
        }
        EquipmentSlot hand = EquipmentSlot.MAINHAND;
        ItemStack main = entity.getMainHandItem();
        ItemStack off = entity.getOffhandItem();
        if (!ranged(main) && ranged(off)) {
            hand = EquipmentSlot.OFFHAND;
        }
        var inventory = pillager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack replacement = inventory.getItem(slot);
            if (HumanProjectileItems.category(replacement) == weapon) {
                return Optional.of(new Exchange(pillager, hand, slot,
                        pillager.getItemBySlot(hand), replacement));
            }
        }
        return Optional.empty();
    }

    private static boolean ranged(ItemStack stack) {
        // 类别来自行矢数据标签，允许数据包扩展弓弩物品。
        return HumanProjectileItems.category(stack)
                != ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED;
    }

    record Exchange(Pillager entity, EquipmentSlot hand, int slot, ItemStack original, ItemStack replacement) {
        HeldItems preview(HeldItems current) {
            // 只替换预览中的持武器手，另一只手仍参与弹药/空手校验。
            var items = current.toBuilder();
            var item = TechniqueWorldItems.item(replacement);
            var category = HumanProjectileItems.category(replacement);
            if (hand == EquipmentSlot.MAINHAND) {
                items.setMainHand(item);
                items.setMainHandProjectileWeapon(category);
            } else {
                items.setOffHand(item);
                items.setOffHandProjectileWeapon(category);
            }
            return items.setCanFindAmmunition(true /* mobAmmunition */).build();
        }

        void apply() {
            // 候选与执行必须仍指向相同物品，防止把过期预览覆盖到新库存。
            if (entity.getItemBySlot(hand) != original || entity.getInventory().getItem(slot) != replacement) {
                throw new IllegalStateException("掠夺者换兵前物品已变化");
            }
            entity.stopUsingItem();
            entity.setChargingCrossbow(false /* charging */);
            entity.getInventory().setItem(slot, original);
            entity.setItemSlot(hand, replacement);
        }

        void restore() {
            // 施放被拒时恢复同一对物品；成功施放后保留新武器，不强制换回。
            if (entity.getItemBySlot(hand) == replacement && entity.getInventory().getItem(slot) == original) {
                entity.setItemSlot(hand, original);
                entity.getInventory().setItem(slot, replacement);
            }
        }
    }
}
