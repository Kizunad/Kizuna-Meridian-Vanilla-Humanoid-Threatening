package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.fabric.BirthVolumes.Equipment;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.inventory.MobInventories;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.InventoryCarrier;

/** 原版装备、库存与背囊就绪后结清出生配装，暂缺区域服务时保留原出生记录。 */
final class BirthEquipment {
    private static final Identifier AFTER_PACK = Identifier.parse("meridian_humanoid:birth_equipment");

    private BirthEquipment() { }

    static void initialize() {
        Set<Mob> waiting = new HashSet<>();
        ServerEntityEvents.ENTITY_LOAD.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_PACK);
        ServerEntityEvents.ENTITY_LOAD.register(AFTER_PACK, (entity, level) -> {
            if (entity instanceof Mob mob && !finish(mob)) {
                waiting.add(mob);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> waiting.remove(entity));
        ServerTickEvents.END_SERVER_TICK.register(server -> waiting.removeIf(BirthEquipment::finish));
    }

    private static boolean finish(Mob mob) {
        var access = (HumanoidEntityAccess) mob;
        var saved = access.meridian$birthVolumes();
        if (!access.meridian$awaitingVolumeBirth() && (saved == null || saved.equipment() != Equipment.PENDING)) {
            return true;
        }
        var species = HumanoidSpeciesHooks.claimed(mob).orElse(null);
        if (species == null || !HumanoidSpecies.values().contains(species) || !mob.isAlive()
                || access.meridian$humanoidLoadFailed()) {
            return true;
        }
        if (!HumanoidFabric.isReady() || !TechniqueFabric.running() || HumanoidFabric.isStopping()
                || access.meridian$humanoidLoading()) {
            return false;
        }
        // 旧 PENDING 也先绑定已存身体；否则再次存盘会原样写回待处理的原始 NBT。
        if (access.meridian$humanoidBody() == null) {
            try {
                HumanoidHealthHost.bodyState(mob, species, HumanoidFabric.now());
            } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
                return false;
            } catch (IllegalArgumentException invalidBody) {
                HumanoidFabric.report(mob, invalidBody);
                return true;
            }
        }
        var grant = access.meridian$birthVolumes();
        if (grant != null && grant.equipment() == Equipment.PENDING && MobInventories.state(mob).available()) {
            var outcome = equip(mob, grant);
            access.meridian$birthVolumes(new BirthVolumes(grant.tier(), grant.volumes(), outcome));
        }
        return true;
    }

    private static Equipment equip(Mob mob, BirthVolumes grant) {
        String volume = grant.volumes().getFirst();
        var requirement = BirthWeapon.forVolume(volume);
        if (requirement.isEmpty()) {
            return Equipment.NOT_REQUIRED;
        }
        var weapon = requirement.get();
        var inventory = MobInventories.inventory(mob);
        if (weapon.matches(mob.getMainHandItem()) || weapon.matches(mob.getOffhandItem())
                || inventory.snapshot().stream().anyMatch(weapon::matches) || hasInVanillaInventory(mob, weapon)) {
            return Equipment.ALREADY_CARRIED;
        }
        var stack = weapon.create(mob.getUUID(), grant.tier(), volume);
        if (stack.isEmpty()) {
            return Equipment.NO_MATCH;
        }
        return inventory.insertBirthWeapon(stack) ? Equipment.PACKED : Equipment.NO_ROOM;
    }

    private static boolean hasInVanillaInventory(Mob mob, BirthWeapon weapon) {
        if (mob instanceof InventoryCarrier carrier) {
            var inventory = carrier.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (weapon.matches(inventory.getItem(slot))) {
                    return true;
                }
            }
        }
        return false;
    }
}
