package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.SlainAdult;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.SlainAdult.FirstStrike;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

final class SlainAdults {
    private static final int MAX_SLAIN_ADULTS = 32;
    private static final TagKey<EntityType<?>> ADULTS = TagKey.create(Registries.ENTITY_TYPE,
            Identifier.parse("meridian_humanoid:possible_fathers"));
    private static final String FIRST = "meridian_humanoid.first_strike.";

    private SlainAdults() { }

    private static boolean eligible(LivingEntity entity) {
        return !entity.isBaby() && !entity.entityTags().contains(HumanoidVisitors.YOUTH)
                && entity.getType().builtInRegistryHolder().is(ADULTS)
                && HumanoidSpeciesHooks.isLivingHumanoid(entity);
    }

    static void hit(VanillaDamageFabric.DamageEvent hit) {
        if (hit.result().health().getDamageResolution().getLossesCount() == 0) {
            return;
        }
        var target = hit.target();
        var attacker = hit.attacker();
        if (attacker instanceof ServerPlayer player && eligible(target)) {
            remember(target, player, FirstStrike.PLAYER);
        } else if (target instanceof ServerPlayer player && attacker != null && eligible(attacker)) {
            remember(attacker, player, FirstStrike.VICTIM);
        }
    }

    private static String prefix(ServerPlayer player) {
        return FIRST + player.getStringUUID() + ".";
    }

    private static void remember(LivingEntity adult, ServerPlayer player, FirstStrike side) {
        String prefix = prefix(player);
        if (adult.entityTags().stream().noneMatch(tag -> tag.startsWith(prefix))) {
            adult.addTag(prefix + side.name());
        }
    }

    static void record(CrisisEngine engine, ServerPlayer player, LivingEntity victim) {
        if (!eligible(victim)) {
            return;
        }
        var name = victim.getCustomName();
        String prefix = prefix(player);
        var first = FirstStrike.UNKNOWN;
        if (victim.entityTags().contains(prefix + FirstStrike.VICTIM.name())) {
            first = FirstStrike.VICTIM;
        } else if (victim.entityTags().contains(prefix + FirstStrike.PLAYER.name())) {
            first = FirstStrike.PLAYER;
        }
        var evidence = SlainAdult.newBuilder()
                .setVictim(victim.getStringUUID())
                .setKiller(player.getStringUUID())
                .setTypeName(victim.getType().getDescriptionId())
                .setFirstStrike(first);
        if (name != null) {
            evidence.setName(name.getString());
        }
        engine.update(player, record -> append(record, evidence.setDiedAt(record.getOnlineTicks()).build()));
    }

    static CrisisRecord append(CrisisRecord record, SlainAdult death) {
        var history = record.getSlainAdultsList();
        String victim = death.getVictim();
        boolean known = history.stream().anyMatch(old -> old.getVictim().equals(victim));
        int size = history.size();
        if (known && size <= MAX_SLAIN_ADULTS) {
            return record;
        }
        int additions = known ? 0 : 1;
        int overflow = size + additions - MAX_SLAIN_ADULTS;
        var updated = record.toBuilder();
        if (overflow > 0) {
            updated.clearSlainAdults();
            updated.addAllSlainAdults(history.subList(overflow, size));
        }
        if (!known) {
            updated.addSlainAdults(death);
        }
        return updated.build();
    }
}
