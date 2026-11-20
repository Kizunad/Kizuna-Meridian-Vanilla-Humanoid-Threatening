package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules.Spawn;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;

/** 待建身实体自己的出生事实。物种要求生成时定档时，和事实一起保存抽定的档位。 */
public record HumanoidBirthPlan(Spawn spawn, Optional<HumanoidTier> selected) {
    public static void capture(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty,
                               EntitySpawnReason reason) {
        var access = (HumanoidEntityAccess) mob;
        var species = HumanoidSpeciesHooks.claimed(mob);
        if (species.isEmpty() || access.meridian$birthPlan() != null || access.meridian$humanoidSavedBody() != null
                || access.meridian$humanoidBody() != null) {
            return;
        }
        var entry = species.get();
        var spawn = new Spawn(entry.entityId(), reason.name(), difficulty.getEffectiveDifficulty(), Optional.empty());
        var roll = entry.bodyRules().spawnRoll();
        Optional<HumanoidTier> selected = Optional.empty();
        if (roll != null) {
            var random = HumanoidBodyGenerator.randomFor(level.getLevel().getSeed(), mob.getUUID());
            selected = Optional.of(roll.select(spawn, random));
        }
        access.meridian$birthPlan(new HumanoidBirthPlan(spawn, selected));
    }

    public static void captureSpawner(Mob mob, DifficultyInstance difficulty, HumanoidTier tier) {
        var access = (HumanoidEntityAccess) mob;
        if (access.meridian$humanoidSavedBody() != null || access.meridian$humanoidBody() != null) {
            return;
        }
        HumanoidSpeciesHooks.claimed(mob).ifPresent(species -> {
            var choice = Optional.of(tier);
            var spawn = new Spawn(species.entityId(), EntitySpawnReason.SPAWNER.name(),
                    difficulty.getEffectiveDifficulty(), choice);
            access.meridian$birthPlan(new HumanoidBirthPlan(spawn, choice));
        });
    }

    public boolean recordsExplicitEggBirth() {
        return spawn.requested().isPresent() && (spawn.reason().equals(EntitySpawnReason.SPAWN_ITEM_USE.name())
                || spawn.reason().equals(EntitySpawnReason.SPAWNER.name()));
    }

    static HumanoidBirthPlan unknown(HumanoidSpecies species) {
        var spawn = new Spawn(species.entityId(), "UNKNOWN", 0, Optional.empty());
        return new HumanoidBirthPlan(spawn, Optional.empty());
    }

    static HumanoidBirthPlan of(HumanoidEntityAccess access, HumanoidSpecies species) {
        var saved = access.meridian$birthPlan();
        if (saved != null && saved.spawn().entityType().equals(species.entityId())) {
            return saved;
        }
        return unknown(species);
    }

    HumanoidBirthPlan requested(HumanoidTier tier) {
        var choice = Optional.of(tier);
        var explicit = new Spawn(spawn.entityType(), spawn.reason(), spawn.difficulty(), choice);
        return new HumanoidBirthPlan(explicit, choice);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("type", spawn.entityType());
        tag.putString("reason", spawn.reason());
        tag.putDouble("difficulty", spawn.difficulty());
        spawn.requested().ifPresent(tier -> tag.putString("requested", tier.id()));
        selected.ifPresent(tier -> tag.putString("selected", tier.id()));
        return tag;
    }

    public static HumanoidBirthPlan load(CompoundTag tag) {
        var requested = tag.getString("requested").map(id -> HumanoidTier.fromId(id).orElseThrow());
        var selected = tag.getString("selected").map(id -> HumanoidTier.fromId(id).orElseThrow());
        var spawn = new Spawn(tag.getString("type").orElseThrow(), tag.getString("reason").orElseThrow(),
                tag.getDouble("difficulty").orElseThrow(), requested);
        return new HumanoidBirthPlan(spawn, selected);
    }
}
