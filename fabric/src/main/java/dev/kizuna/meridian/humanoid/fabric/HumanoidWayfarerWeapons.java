package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.human.fabric.HumanProjectileItems;
import dev.kizuna.meridian.inventory.MobInventory;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueHeldItems;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 类人使用背囊存放备用武器，换兵保留物品的全部组件。 */
final class HumanoidWayfarerWeapons {
    static final ProjectileWeaponCategory BOWS = ProjectileWeaponCategory.BOW;
    static final ProjectileWeaponCategory CROSSBOWS = ProjectileWeaponCategory.CROSSBOW;

    private HumanoidWayfarerWeapons() {
        // 库存由原版实体保存，不另建持久状态。
    }

    static void equipAtBirth(LivingEntity entity, HumanoidTier tier) {
        // 只在新身体出生时补一把弓，读档不会重复补发，也不覆盖原版库存物品。
        if (!(entity instanceof Pillager pillager) || tier != HumanoidTier.TRUE_QI
                || holds(pillager, BOWS) || reserve(pillager, BOWS).isPresent()
                || hasInVanillaInventory(pillager, BOWS)) {
            return;
        }
        var pack = MobInventories.state(pillager);
        if (pack.diagnostic().isPresent()) {
            return;
        }
        var bow = new ItemStack(Items.BOW);
        if (pack.inventory().snapshot().stream()
                .anyMatch(stack -> HumanProjectileItems.category(stack) == BOWS)) {
            return;
        }
        pack.inventory().insert(bow);
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

    static Optional<Exchange> forTechnique(LivingEntity entity, HeldItemRules rules,
                                            TechniqueDefinition definition, HeldItems current) {
        boolean doorBreaking = definition.getEffectsList().stream().anyMatch(TechniqueEffect::hasBreakBlocks);
        // 当前两手已满足同一持物规则时不换手；否则逐槽预览背囊物品。
        if (currentSupports(entity, rules, definition, current)) {
            return Optional.empty();
        }
        if (!(entity instanceof Mob mob)) {
            return Optional.empty();
        }
        var inventory = MobInventories.inventory(mob);
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            ItemStack replacement = inventory.peek(slot);
            if (replacement.isEmpty()) {
                continue;
            }
            if (doorBreaking && !hasDoorBreakingCategory(rules, replacement)) {
                continue;
            }
            for (EquipmentSlot hand : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
                HeldItems preview = preview(current, hand, replacement);
                if (TechniqueHeldItems.find(rules, preview, definition).isPresent()) {
                    ItemStack original = entity.getItemBySlot(hand).copy();
                    return Optional.of(new Exchange(mob, hand, original, replacement,
                            new Storage(slot, storageType(inventory, slot))));
                }
            }
        }
        return Optional.empty();
    }

    static boolean currentSupports(LivingEntity entity, HeldItemRules rules,
                                   TechniqueDefinition definition, HeldItems current) {
        var hand = TechniqueHeldItems.find(rules, current, definition);
        if (hand.isEmpty()) {
            return false;
        }
        boolean doorBreaking = definition.getEffectsList().stream().anyMatch(TechniqueEffect::hasBreakBlocks);
        if (!doorBreaking) {
            return true;
        }
        ItemStack held;
        if (hand.orElseThrow() == Hand.MAIN_HAND) {
            held = entity.getMainHandItem();
        } else {
            held = entity.getOffhandItem();
        }
        return hasDoorBreakingCategory(rules, held);
    }

    private static boolean hasDoorBreakingCategory(HeldItemRules rules, ItemStack replacement) {
        var item = TechniqueWorldItems.item(replacement);
        return rules.getCategoriesList().stream()
                .filter(category -> !category.getItemTagsList().isEmpty())
                .filter(category -> category.hasCanBreakDoors() && category.getCanBreakDoors())
                .anyMatch(category -> category.getItemTagsList().stream().anyMatch(item.getTagsList()::contains));
    }

    static Optional<Exchange> reserve(LivingEntity entity, ProjectileWeaponCategory weapon) {
        // 只预览背囊，不在候选枚举期间改变装备；原来副手持武器时仍用副手换兵。
        if (!(entity instanceof Mob mob)) {
            return Optional.empty();
        }
        EquipmentSlot hand = EquipmentSlot.MAINHAND;
        ItemStack main = entity.getMainHandItem();
        ItemStack off = entity.getOffhandItem();
        if (!ranged(main) && ranged(off)) {
            hand = EquipmentSlot.OFFHAND;
        }
        var inventory = MobInventories.inventory(mob);
        for (int slot = 0; slot < MobInventory.SIZE; slot++) {
            ItemStack replacement = inventory.peek(slot);
            if (HumanProjectileItems.category(replacement) == weapon) {
                return Optional.of(new Exchange(mob, hand, mob.getItemBySlot(hand).copy(), replacement,
                        new Storage(slot, storageType(inventory, slot))));
            }
        }
        return Optional.empty();
    }

    private static HeldItems preview(HeldItems current, EquipmentSlot hand, ItemStack replacement) {
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
        items.setCanFindAmmunition(true /* mobAmmunition */);
        return items.build();
    }

    private static boolean ranged(ItemStack stack) {
        // 类别来自行矢数据标签，允许数据包扩展弓弩物品。
        return HumanProjectileItems.category(stack)
                != ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED;
    }

    private static boolean hasInVanillaInventory(Mob mob, ProjectileWeaponCategory weapon) {
        if (!(mob instanceof InventoryCarrier carrier)) {
            return false;
        }
        var inventory = carrier.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (HumanProjectileItems.category(inventory.getItem(slot)) == weapon) {
                return true;
            }
        }
        return false;
    }

    private static StorageType storageType(MobInventory inventory, int slot) {
        if (inventory.isBirthWeapon(slot)) {
            return StorageType.BIRTH_WEAPON;
        }
        return StorageType.BACKPACK_ITEM;
    }

    record Exchange(Mob entity, EquipmentSlot hand, ItemStack original, ItemStack replacement, Storage storage) {
        int slot() {
            return storage.slot();
        }

        boolean birthWeapon() {
            return storage.type() == StorageType.BIRTH_WEAPON;
        }

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
            MobInventory inventory = MobInventories.inventory(entity);
            ItemStack current = entity.getItemBySlot(hand);
            ItemStack stored = inventory.peek(storage.slot());
            if (!ItemStack.isSameItemSameComponents(current, original)
                    || !ItemStack.isSameItemSameComponents(stored, replacement)) {
                throw new IllegalStateException("背囊换兵前物品已变化");
            }
            entity.stopUsingItem();
            if (entity instanceof Pillager pillager) {
                pillager.setChargingCrossbow(false /* charging */);
            }
            ItemStack previous = inventory.exchange(storage.slot(), original.copy());
            if (!ItemStack.isSameItemSameComponents(previous, replacement)) {
                throw new IllegalStateException("背囊换兵返回物品不匹配");
            }
            entity.setItemSlot(hand, replacement.copy());
        }

        void restore() {
            // 收势时将已更新的替换物放回背囊，原手持物恢复到原槽位。
            var inventory = MobInventories.inventory(entity);
            ItemStack stored = inventory.peek(storage.slot());
            ItemStack current = entity.getItemBySlot(hand);
            if (!ItemStack.isSameItemSameComponents(stored, original)
                    || !ItemStack.isSameItem(current, replacement)) {
                return;
            }
            ItemStack previous = inventory.exchange(storage.slot(), current.copy());
            if (!ItemStack.isSameItemSameComponents(previous, original)) {
                throw new IllegalStateException("背囊还手返回物品不匹配");
            }
            if (storage.type() == StorageType.BIRTH_WEAPON) {
                inventory.restoreBirthWeapon(storage.slot());
            }
            entity.setItemSlot(hand, original.copy());
        }
    }

    record Storage(int slot, StorageType type) {
    }

    enum StorageType {
        BIRTH_WEAPON,
        BACKPACK_ITEM
    }
}
