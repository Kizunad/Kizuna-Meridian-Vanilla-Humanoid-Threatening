package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.HumanProjectileItems;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumePool;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

record BirthWeapon(HeldItemRules rules, HeldItemCategory category) {
    static Optional<BirthWeapon> forVolume(String volumeId) {
        var registry = TechniqueFabric.service().registry();
        var volume = registry.findVolume(volumeId).orElseThrow();
        var references = volume.getTechniquesList();
        var book = registry.bookOf(references.getFirst()).orElseThrow();
        var rules = book.getHeldItemRules();
        var projectile = references.stream().map(ref -> registry.find(ref).orElseThrow())
                .filter(TechniqueDefinition::hasProjectileWeapon)
                .map(TechniqueDefinition::getProjectileWeapon)
                .findFirst().orElse(ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED);
        return rules.getCategoriesList().stream()
                .filter(entry -> entry.getItemTagsCount() > 0)
                .filter(entry -> entry.getProjectileWeapon() == projectile)
                .min(Comparator.comparingInt(HeldItemCategory::getSelectionPriority))
                .map(entry -> new BirthWeapon(rules, entry));
    }

    boolean matches(ItemStack stack) {
        if (!TechniqueWorldItems.usable(stack) || !TechniqueWorldItems.category(rules, stack).equals(category)) {
            return false;
        }
        return !category.hasProjectileWeapon()
                || HumanProjectileItems.category(stack) == category.getProjectileWeapon();
    }

    ItemStack create(UUID entity, HumanoidTier tier, String volume) {
        var repair = repairMaterial(entity, tier, volume);
        return category.getItemTagsList().stream()
                .map(id -> TagKey.create(Registries.ITEM, Identifier.parse(id)))
                .flatMap(tag -> StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(tag).spliterator(),
                        false /* parallel */))
                .map(Holder::value)
                .distinct()
                .map(ItemStack::new)
                .filter(this::matches)
                .filter(stack -> stack.getMaxStackSize() == 1 && !stack.isEnchanted())
                .filter(stack -> repair.isEmpty() || stack.isValidRepairItem(repair))
                .min(Comparator.comparingDouble(stack -> VolumePool.sample(entity, "weapon/" + volume + "/"
                        + BuiltInRegistries.ITEM.getKey(stack.getItem()))))
                .orElse(ItemStack.EMPTY);
    }

    private ItemStack repairMaterial(UUID entity, HumanoidTier tier, String volume) {
        if (category.hasProjectileWeapon()) {
            return ItemStack.EMPTY;
        }
        var config = HumanoidBrain.config().volumePool().materials();
        if (VolumePool.sample(entity, "diamond/" + volume) < config.diamondChance()) {
            return new ItemStack(Items.DIAMOND);
        }
        if (tier == HumanoidTier.TRUE_QI
                || VolumePool.sample(entity, "iron/" + volume) < config.wanderingIronChance()) {
            return new ItemStack(Items.IRON_INGOT);
        }
        return new ItemStack(Items.COBBLESTONE);
    }
}
