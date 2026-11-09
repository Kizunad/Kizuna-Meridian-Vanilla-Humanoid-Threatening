package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.WitchRules;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** 施放期间借用女巫主手；正常、拒绝与所有生命周期出口都恢复同一原物。 */
public final class HumanoidWitchHands {
    private static final String SAVED_HAND = "meridian_witch_original_hand";
    private static final Map<UUID, Held> held = new HashMap<>();
    private static final Map<Witch, Long> celebrations = new HashMap<>();

    record Plan(String technique, BrewKind kind, LivingEntity target, BrewPartGroup aim) { }
    private record Held(Witch witch, ItemStack original, ItemStack bottle, String castId, Plan plan) { }

    private HumanoidWitchHands() {
        // 主手接管只存在于正在施放的实体，持久化仅保存需要恢复的原物。
    }

    static void initialize() {
        // 结束事件是主路径，卸载和停服作为宿主异常时的兜底恢复。
        TechniqueFabric.CAST_ENDED.register(event -> {
            Held current = held.get(event.entityUuid());
            if (current != null && current.castId().equals(event.castId())) {
                restore(current.witch());
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Witch witch) {
                restore(witch);
                celebrations.remove(witch);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Held current : List.copyOf(held.values())) {
                restore(current.witch());
            }
            celebrations.clear();
        });
        BrewResidues.TRIGGERED.register((source, kind, count) -> {
            if (source instanceof Witch witch) {
                celebrations.put(witch, witch.level().getGameTime()
                        + HumanoidWitchAi.rules().sound().celebrateDelayTicks());
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> tickSounds());
    }

    static boolean cast(Witch witch, Plan plan, BrewQuality quality, TechniqueReference reference) {
        // 选式通过候选预检后才放瓶，拒绝或异常必须马上归还主手。
        UUID witchId = witch.getUUID();
        if (witch.isDrinkingPotion() || held.containsKey(witchId)) {
            return false;
        }
        String technique = plan.technique();
        BrewUse use = BrewUse.SPLASH;
        if (technique.equals(HumanoidWitchAi.IMBIBE) || technique.equals(HumanoidWitchAi.ANOINT)) {
            use = BrewUse.DRINK;
        }
        ItemStack bottle = BrewItemStacks.create(plan.kind(), quality, use);
        var current = new Held(witch, witch.getMainHandItem(), bottle, UUID.randomUUID().toString(), plan);
        held.put(witchId, current);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        try {
            aim(witch);
            var body = HumanoidTechniqueHost.prepare(witch).body().snapshot();
            var request = CastRequest.newBuilder()
                    .setBodyId(body.getId())
                    .setTechnique(reference)
                    .setCastId(current.castId());
            if (plan.target() != null) {
                request.setTargetId(plan.target().getStringUUID());
            }
            var outcome = TechniqueFabric.service().cast(witchId, request.build());
            if (!outcome.hasBinding()) {
                restore(witch);
                return false;
            }
            if (technique.equals(HumanoidWitchAi.IMBIBE)) {
                sound(witch, SoundEvents.WITCH_DRINK);
            }
            return true;
        } catch (RuntimeException failure) {
            restore(witch);
            throw failure;
        }
    }

    public static boolean occupied(LivingEntity entity) {
        // 原版投药和自喝只需只读接管状态，不递归查询功法宿主。
        return held.containsKey(entity.getUUID());
    }

    public static void restore(LivingEntity entity) {
        // 同一原 ItemStack 归还原主手，生物施放没有扣瓶或复制原物。
        Held current = held.remove(entity.getUUID());
        if (current != null) {
            current.witch().setItemSlot(EquipmentSlot.MAINHAND, current.original());
        }
    }

    public static void aim(Witch witch) {
        // 保持实际视线指向所选部位，Brewing 在出手时按通常视线规则冻结分段。
        Held current = held.get(witch.getUUID());
        if (current == null) {
            return;
        }
        Plan plan = current.plan();
        if (plan.target() == null) {
            return;
        }
        LivingEntity target = plan.target();
        WitchRules rules = HumanoidWitchAi.rules();
        double height = rules.aim().torsoHeight();
        if (plan.aim() == BrewPartGroup.LEGS) {
            height = rules.aim().legsHeight();
        } else if (plan.aim() == BrewPartGroup.HEAD_NECK) {
            height = rules.aim().headHeight();
        }
        Vec3 point = target.position().add(0, target.getBbHeight() * height, 0);
        witch.lookAt(Anchor.EYES, point);
        witch.setYHeadRot(witch.getYRot());
        witch.getLookControl().setLookAt(point.x, point.y, point.z);
    }

    public static void save(LivingEntity entity, ValueOutput output) {
        // 区块可能先写盘后触发卸载；额外写原物，重载时覆盖临时瓶，自动存档不打断施放。
        Held current = held.get(entity.getUUID());
        if (current != null) {
            output.store(SAVED_HAND, ItemStack.OPTIONAL_CODEC, current.original());
        }
    }

    public static void load(LivingEntity entity, ValueInput input) {
        // 旧动作由功法宿主恢复清理，装备先恢复为持久化的原主手。
        input.read(SAVED_HAND, ItemStack.OPTIONAL_CODEC)
                .ifPresent(original -> entity.setItemSlot(EquipmentSlot.MAINHAND, original));
    }

    private static void tickSounds() {
        // 笑声以成功催发时刻计时；目标是否继续带药不影响已发生的催发。
        celebrations.entrySet().removeIf(entry -> {
            Witch witch = entry.getKey();
            if (!witch.isAlive() || witch.isRemoved()) {
                return true;
            }
            if (witch.level().getGameTime() < entry.getValue()) {
                return false;
            }
            sound(witch, SoundEvents.WITCH_CELEBRATE);
            return true;
        });
    }

    private static void sound(Witch witch, SoundEvent sound) {
        // 女巫额外声音固定敌对生物类别，通用功法声音仍沿各自目录入口。
        WitchRules rules = HumanoidWitchAi.rules();
        witch.level().playSound(null, witch.getX(), witch.getY(), witch.getZ(), sound,
                SoundSource.HOSTILE, (float) rules.sound().volume(), (float) rules.sound().pitch());
    }
}
