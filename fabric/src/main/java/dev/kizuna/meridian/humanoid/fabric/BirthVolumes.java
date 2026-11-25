package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.VolumePool;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

/** 同一具新身体只决定一次的外卷和配装进度。空卷也有明确的出生档位。 */
public record BirthVolumes(HumanoidTier tier, List<String> volumes, Equipment equipment) {
    private static final Codec<List<String>> VOLUMES = Codec.STRING.listOf();
    public enum Equipment { NONE, PENDING, NOT_REQUIRED, ALREADY_CARRIED, PACKED, NO_ROOM, NO_MATCH }

    public BirthVolumes {
        volumes = List.copyOf(volumes);
        if (volumes.isEmpty() != (equipment == Equipment.NONE)) {
            throw new IllegalArgumentException("外卷与配装状态不匹配");
        }
    }

    static void select(LivingEntity entity, HumanoidSpecies species, HumanoidBodyState body) {
        var access = (HumanoidEntityAccess) entity;
        if (!access.meridian$awaitingVolumeBirth() || !supports(entity, species)) {
            return;
        }
        if (HumanoidSwordForms.species(entity)) {
            var tier = HumanoidTier.of(body.snapshot());
            var volumes = HumanoidSwordForms.birthVolumes(entity, tier);
            var equipment = volumes.isEmpty() ? Equipment.NONE : Equipment.ALREADY_CARRIED;
            access.meridian$birthVolumes(new BirthVolumes(tier, volumes, equipment));
        } else {
            access.meridian$birthVolumes(draw(entity, species, body));
        }
    }

    public static boolean supports(LivingEntity entity, HumanoidSpecies species) {
        // 待定的出生卷要随区块和实体一同保存。
        return HumanoidSpecies.values().contains(species) || HumanoidSwordForms.species(entity);
    }

    static BirthVolumes draw(LivingEntity entity, HumanoidSpecies species, HumanoidBodyState body) {
        var tier = HumanoidTier.of(body.snapshot());
        var pool = HumanoidBrain.config().volumePool();
        var side = entity.getMainArm() == HumanoidArm.LEFT ? HandSide.LEFT : HandSide.RIGHT;
        var selected = pool.draw(entity.getUUID(), species.entityId(), tier, id -> usable(body, side, id));
        var equipment = selected.isEmpty() ? Equipment.NONE : Equipment.PENDING;
        return new BirthVolumes(tier, selected, equipment);
    }

    static boolean usable(HumanoidBodyState state, HandSide side, String volume) {
        var body = state.currentBody();
        var registry = TechniqueFabric.service().registry();
        for (var reference : registry.findVolume(volume).orElseThrow().getTechniquesList()) {
            var definition = registry.find(reference).orElseThrow();
            if (!registry.applies(reference, body.template()) || !HumanoidRepertoires.combatAtBirth(body, definition)) {
                continue;
            }
            var binding = TechniqueBinder.bind(body, definition, Hand.MAIN_HAND, side);
            if (binding.hasBinding() && HumanoidRepertoires.payable(body, definition, binding.getBinding())) {
                return true;
            }
        }
        return false;
    }

    static void validate(VolumePool pool, TechniqueRegistry registry) {
        for (var book : pool.books()) {
            for (String upper : book.upper()) {
                var entry = volume(registry, book.id(), upper);
                if (entry.getPrerequisiteVolumeIdsCount() != 0) {
                    throw new IllegalArgumentException("入门卷有前置：" + upper);
                }
                book.middle().ifPresent(id -> {
                    var middle = volume(registry, book.id(), id);
                    if (!List.of(upper).containsAll(middle.getPrerequisiteVolumeIdsList())) {
                        throw new IllegalArgumentException("进阶卷不能接在入门卷后：" + id);
                    }
                });
            }
        }
    }

    private static TechniqueVolume volume(TechniqueRegistry registry, String book, String id) {
        return registry.findVolume(id).filter(entry -> entry.getBookId().equals(book))
                .orElseThrow(() -> new IllegalArgumentException("卷池中的卷不存在或不属于 " + book + "：" + id));
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("tier", tier.id());
        tag.store("volumes", VOLUMES, volumes);
        tag.putString("equipment", equipment.name());
        return tag;
    }

    public static BirthVolumes load(CompoundTag tag) {
        var tier = tag.getString("tier").flatMap(HumanoidTier::fromId).orElseThrow();
        var volumes = tag.read("volumes", VOLUMES).orElseThrow();
        var equipment = Equipment.valueOf(tag.getString("equipment").orElseThrow());
        return new BirthVolumes(tier, volumes, equipment);
    }
}
